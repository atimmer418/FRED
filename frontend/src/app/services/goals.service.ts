import { Injectable, signal } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { finalize, shareReplay, tap } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { DeviceIdService } from './device-id.service';
import { AuthService } from './auth.service';
import { Goal, GoalWrite, LegacyGoalV2 } from '../models/goal.model';
import { ACTIVE_GOALS_MOCK, mockGoals } from './dev/goals-mock';

// Existing imports of Goal, GoalIcon and friends from this file keep resolving.
export * from '../models/goal.model';

// ─── Schema version ──────────────────────────────────────────────────────────
// SCHEMA_VERSION covers the profile prefs and the legacy v2 localStorage goals
// (read only, kept for the one-time import to the backend).
const SCHEMA_VERSION = 2;
const KEY_GOALS_PREFIX = `fred.goals.v${SCHEMA_VERSION}.`;
const KEY_PREFS_PREFIX = `fred.profilePrefs.v${SCHEMA_VERSION}.`;
const GOALS_TTL_MS = 60_000;

// ─── Types ───────────────────────────────────────────────────────────────────

export type RebalanceSchedule = 'Quarterly' | 'Semi-annual' | 'Annual';

export interface ProfilePrefs {
  rebalanceSchedule: RebalanceSchedule;
}

// ─── Defaults ────────────────────────────────────────────────────────────────

const DEFAULT_PREFS: ProfilePrefs = {
  rebalanceSchedule: 'Quarterly',
};

// ─── Service ─────────────────────────────────────────────────────────────────

/**
 * GoalsService
 *
 * Backend-backed goals store (/api/goals) exposing a signal, plus the
 * localStorage profile prefs and a read-only reader for legacy v2 goals.
 */
@Injectable({ providedIn: 'root' })
export class GoalsService {
  private readonly apiUrl = `${environment.backendApiUrl}/goals`;

  readonly goals = signal<Goal[]>([]);
  readonly loaded = signal(false);

  private entry: { fetchedAt: number; userId: string } | null = null;
  private inFlight$: Observable<Goal[]> | null = null;

  constructor(
    private http: HttpClient,
    private deviceIdService: DeviceIdService,
    private authService: AuthService
  ) {
    this.authService.isLoggedIn$.subscribe(loggedIn => {
      if (!loggedIn) {
        this.clearMemory();
      }
    });
  }

  // ── Backend store ──────────────────────────────────────────────────────────

  /** GET the user's goals. Cached for 60 s per user, single flight. */
  load(): Observable<Goal[]> {
    if (this.useMock()) return this.mockLoad();
    const userId = localStorage.getItem('userId') ?? '';
    if (!userId) return of([]);
    if (
      this.entry &&
      this.entry.userId === userId &&
      Date.now() - this.entry.fetchedAt < GOALS_TTL_MS
    ) {
      return of(this.goals());
    }
    return this.fetch(userId);
  }

  /** Same as load() but ignores the TTL. */
  refresh(): Observable<Goal[]> {
    if (this.useMock()) return this.mockLoad();
    const userId = localStorage.getItem('userId') ?? '';
    if (!userId) return of([]);
    return this.fetch(userId);
  }

  create(body: GoalWrite): Observable<Goal> {
    return this.http.post<Goal>(this.apiUrl, body, { headers: this.getAuthHeaders() }).pipe(
      tap(created => {
        this.invalidate();
        this.goals.update(list => [...list, created]);
      })
    );
  }

  update(id: number, body: GoalWrite): Observable<Goal> {
    return this.http.put<Goal>(`${this.apiUrl}/${id}`, body, { headers: this.getAuthHeaders() }).pipe(
      tap(updated => {
        this.invalidate();
        this.goals.update(list => list.map(g => g.id === updated.id ? updated : g));
      })
    );
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`, { headers: this.getAuthHeaders() }).pipe(
      tap(() => {
        this.invalidate();
        this.goals.update(list => list.filter(g => g.id !== id));
      })
    );
  }

  /** One-time upload of legacy v2 localStorage goals. */
  importLegacy(goals: LegacyGoalV2[]): Observable<Goal[]> {
    const body = {
      goals: goals.map(g => ({
        clientId: g.id,
        name: g.name,
        icon: g.icon,
        targetAmount: g.targetAmount,
        targetYear: g.targetYear,
        startingValue: g.startingValue,
        completed: g.completed,
      })),
    };
    return this.http.post<Goal[]>(`${this.apiUrl}/import`, body, { headers: this.getAuthHeaders() }).pipe(
      tap(imported => {
        this.invalidate();
        this.goals.update(list => [...list, ...imported]);
      })
    );
  }

  /** Drop cached goals and the TTL entry (runs on logout). */
  clearMemory(): void {
    this.invalidate();
    this.goals.set([]);
    this.loaded.set(false);
  }

  // ── Legacy v2 localStorage goals (read only) ───────────────────────────────

  /** Returns the legacy v2 goals for the user, or [] on any error. */
  readLegacyV2(userId: string): LegacyGoalV2[] {
    if (!userId) return [];
    try {
      const raw = localStorage.getItem(KEY_GOALS_PREFIX + userId);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(g => this._isValidLegacyGoal(g));
    } catch {
      return [];
    }
  }

  clearLegacyV2(userId: string): void {
    if (!userId) return;
    try {
      localStorage.removeItem(KEY_GOALS_PREFIX + userId);
    } catch {
      // Storage unavailable, silently swallow.
    }
  }

  // ── Profile prefs (rebalance schedule) ────────────────────────────────────

  getPrefs(userId: string): ProfilePrefs {
    if (!userId) return { ...DEFAULT_PREFS };
    try {
      const raw = localStorage.getItem(KEY_PREFS_PREFIX + userId);
      if (!raw) return { ...DEFAULT_PREFS };
      const parsed = JSON.parse(raw);
      if (!this._isValidPrefs(parsed)) return { ...DEFAULT_PREFS };
      return parsed;
    } catch {
      return { ...DEFAULT_PREFS };
    }
  }

  savePrefs(userId: string, prefs: ProfilePrefs): void {
    if (!userId) return;
    try {
      localStorage.setItem(KEY_PREFS_PREFIX + userId, JSON.stringify(prefs));
    } catch {
      // Storage unavailable, silently swallow.
    }
  }

  // ── Private ────────────────────────────────────────────────────────────────

  /** Dev-only: true when the local build has a goals mock toggled on (always false under Karma). */
  private useMock(): boolean {
    return environment.local && !!ACTIVE_GOALS_MOCK;
  }

  private mockLoad(): Observable<Goal[]> {
    const list = mockGoals(new Date());
    this.goals.set(list);
    this.loaded.set(true);
    return of(list);
  }

  private fetch(userId: string): Observable<Goal[]> {
    if (this.inFlight$) return this.inFlight$;
    const obs: Observable<Goal[]> = this.http.get<Goal[]>(this.apiUrl, { headers: this.getAuthHeaders() }).pipe(
      tap(list => {
        this.goals.set(list);
        this.loaded.set(true);
        this.entry = { fetchedAt: Date.now(), userId };
      }),
      finalize(() => {
        if (this.inFlight$ === obs) {
          this.inFlight$ = null;
        }
      }),
      shareReplay(1)
    );
    this.inFlight$ = obs;
    return obs;
  }

  private invalidate(): void {
    this.entry = null;
    this.inFlight$ = null;
  }

  private getAuthHeaders(): HttpHeaders {
    let headers = new HttpHeaders();
    const token = JwtTokenUtils.getValidJwtToken();

    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }

    const deviceId = this.deviceIdService.getDeviceId();
    headers = headers.set('X-Device-ID', deviceId);

    return headers;
  }

  private _isValidLegacyGoal(g: any): g is LegacyGoalV2 {
    return (
      g != null &&
      typeof g === 'object' &&
      typeof g.id === 'string' &&
      typeof g.name === 'string' &&
      typeof g.icon === 'string' &&
      typeof g.targetAmount === 'number' &&
      typeof g.targetYear === 'number' &&
      typeof g.startingValue === 'number' &&
      typeof g.perPaycheckOutsideFRED === 'number' &&
      typeof g.completed === 'boolean'
    );
  }

  private _isValidPrefs(p: any): p is ProfilePrefs {
    return (
      p != null &&
      typeof p === 'object' &&
      ['Quarterly', 'Semi-annual', 'Annual'].includes(p.rebalanceSchedule)
    );
  }
}
