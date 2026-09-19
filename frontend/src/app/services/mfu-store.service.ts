import { Injectable } from '@angular/core';
import { Observable, of, shareReplay } from 'rxjs';
import { catchError, finalize, map, switchMap, tap } from 'rxjs/operators';
import { MonthlyFreedomUpdateService, MonthlyFreedomUpdateData } from './monthly-freedom-update.service';
import { AuthService } from './auth.service';

/** Result of the auto-popup check(+generate) single-flight. */
export interface MfuAutoSession {
  shouldShow: boolean;
  hasMfuHistory: boolean;
  /** Full preloaded payload when shouldShow; null only if /generate errored (caller shows a shell). */
  data: MonthlyFreedomUpdateData | null;
}

interface MfuSnapshot {
  userId: string;
  savedAt: number;
  data: MonthlyFreedomUpdateData;
}

/**
 * In-memory single-flight + localStorage snapshot for the Monthly Freedom Update, mirroring
 * PortfolioStoreService so the popup and the tab3 reopen open instantly (no "Preparing your update…"):
 *
 *  - ensureAutoSession() — single-flight GET /check (+ /generate when shouldShow). Shared by the
 *    dashboard and tab3 so they never double-fetch or double-pop. The in-flight is nulled in
 *    finalize() (NOT a permanent shareReplay) so a transient /check error is retried, not replayed.
 *  - peekSnapshot()/persistSnapshot() — versioned 'fred.mfu.v1.<userId>' reopen payload for instant
 *    paint; userId-scoped so a foreign snapshot can never hydrate.
 *  - loadReopen$() — single-flight reopen fetch that re-persists the snapshot.
 *  - tryClaimAutoPopup(month) — one-shot per month so only one presenter shows the auto popup.
 */
@Injectable({ providedIn: 'root' })
export class MfuStoreService {
  private static readonly TTL_MS = 60_000;
  // Bump v1 -> v2 whenever MonthlyFreedomUpdateData changes shape so an old snapshot can't hydrate.
  private static readonly SNAP_PREFIX = 'fred.mfu.v1.';

  private entry: { session: MfuAutoSession; fetchedAt: number; userId: string; month: string } | null = null;
  private inFlight$: Observable<MfuAutoSession> | null = null;
  private reopenInFlight$: Observable<MonthlyFreedomUpdateData> | null = null;

  // One auto popup per (session, month) so dashboard and tab3 can't both present the same MFU.
  private claimedMonth: string | null = null;
  private fontsLoaded = false;

  constructor(
    private mfuService: MonthlyFreedomUpdateService,
    private authService: AuthService
  ) {
    this.authService.isLoggedIn$.subscribe(loggedIn => {
      if (!loggedIn) {
        this.clearMemory();
      }
    });
  }

  private uid(): string {
    return localStorage.getItem('userId') ?? '';
  }

  private currentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  }

  /** Fresh when the entry belongs to the current user, is the current month, and is within TTL. */
  private isFresh(): boolean {
    if (!this.entry) return false;
    if (this.entry.userId !== this.uid()) return false;
    if (this.entry.month !== this.currentMonth()) return false; // catches a foreground month rollover
    return Date.now() - this.entry.fetchedAt < MfuStoreService.TTL_MS;
  }

  /**
   * Single-flight check(+generate) for the auto popup. Resolves { shouldShow, hasMfuHistory, data }.
   * A /check error is NOT cached (finalize nulls the in-flight), so a later trigger retries rather
   * than replaying a poisoned session. A /generate error resolves data:null with shouldShow:true so
   * the caller can still present a self-loading shell.
   */
  ensureAutoSession(): Observable<MfuAutoSession> {
    if (this.isFresh()) return of(this.entry!.session);
    if (this.inFlight$) return this.inFlight$;

    const obs = this.mfuService.checkShouldShow().pipe(
      switchMap(chk => {
        const hasMfuHistory = !!chk.hasMfuHistory;
        if (!chk.shouldShow) {
          return of<MfuAutoSession>({ shouldShow: false, hasMfuHistory, data: null });
        }
        return this.mfuService.generateUpdate(false).pipe(
          map(data => ({ shouldShow: true, hasMfuHistory, data } as MfuAutoSession)),
          catchError(() => of<MfuAutoSession>({ shouldShow: true, hasMfuHistory, data: null }))
        );
      }),
      tap(session => {
        this.entry = { session, fetchedAt: Date.now(), userId: this.uid(), month: this.currentMonth() };
      }),
      finalize(() => {
        if (this.inFlight$ === obs) this.inFlight$ = null;
      }),
      shareReplay(1)
    );
    this.inFlight$ = obs;
    return obs;
  }

  /** Late, synchronous test-and-set: only the first caller for a given month presents the popup. */
  tryClaimAutoPopup(month: string): boolean {
    if (this.claimedMonth === month) return false;
    this.claimedMonth = month;
    return true;
  }

  /** Single-flight reopen fetch; persists the snapshot on success for the next instant reopen. */
  loadReopen$(): Observable<MonthlyFreedomUpdateData> {
    if (this.reopenInFlight$) return this.reopenInFlight$;
    const obs = this.mfuService.generateUpdate(true).pipe(
      tap(data => this.persistSnapshot(data)),
      finalize(() => {
        if (this.reopenInFlight$ === obs) this.reopenInFlight$ = null;
      }),
      shareReplay(1)
    );
    this.reopenInFlight$ = obs;
    return obs;
  }

  /** Await the Material Symbols font once (capped ~1.5s) so icons never tofu-reflow after present. */
  async ensureFonts(): Promise<void> {
    if (this.fontsLoaded) return;
    try {
      const fonts = (document as any).fonts;
      if (fonts?.load) {
        await Promise.race([
          fonts.load('24px "Material Symbols Outlined"'),
          new Promise(resolve => setTimeout(resolve, 1500))
        ]);
      }
    } catch {
      /* a font failure must never block the modal */
    }
    this.fontsLoaded = true;
  }

  /** Synchronous read of the persisted reopen snapshot for the CURRENT user, or null. */
  peekSnapshot(): MonthlyFreedomUpdateData | null {
    if (this.mfuService.isMockActive()) return null; // DEV mock: never hydrate from a stored recap
    const userId = this.uid();
    if (!userId) return null;
    try {
      const raw = localStorage.getItem(MfuStoreService.SNAP_PREFIX + userId);
      if (!raw) return null;
      const snap = JSON.parse(raw) as MfuSnapshot;
      return (snap && snap.userId === userId && snap.data && !!snap.data.periodStart) ? snap.data : null;
    } catch {
      return null;
    }
  }

  persistSnapshot(data: MonthlyFreedomUpdateData): void {
    if (this.mfuService.isMockActive()) return; // DEV mock: a mocked recap must never outlive the toggle
    const userId = this.uid();
    if (!userId || !data || !data.periodStart) return;
    const snap: MfuSnapshot = { userId, savedAt: Date.now(), data };
    try {
      localStorage.setItem(MfuStoreService.SNAP_PREFIX + userId, JSON.stringify(snap));
    } catch {
      /* quota / private mode — persistence is best-effort */
    }
  }

  /** Wipe in-memory state on logout. The userId-scoped snapshot is left for the same user's next launch. */
  clearMemory(): void {
    this.entry = null;
    this.inFlight$ = null;
    this.reopenInFlight$ = null;
    this.claimedMonth = null;
  }
}
