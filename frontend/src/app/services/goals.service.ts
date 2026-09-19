import { Injectable } from '@angular/core';

// ─── Schema version ──────────────────────────────────────────────────────────
// Bump SCHEMA_VERSION whenever the Goal or Prefs shape changes.
// Old data is silently discarded — corrupt-tolerant, never crashes.
// v2 (2026-08-13): startingValue + perPaycheckOutsideFRED (replaces
// monthlyOutsideFRED) + completed.
const SCHEMA_VERSION = 2;
const KEY_GOALS_PREFIX = `fred.goals.v${SCHEMA_VERSION}.`;
const KEY_PREFS_PREFIX = `fred.profilePrefs.v${SCHEMA_VERSION}.`;

// ─── Types ───────────────────────────────────────────────────────────────────

/** Icons allowed for user-created goals. */
export type GoalIcon =
  | 'home'
  | 'health_and_safety'
  | 'flag'
  | 'balance'
  | 'savings'
  | 'school'
  | 'directions_car';

export type RebalanceSchedule = 'Quarterly' | 'Semi-annual' | 'Annual';

export interface Goal {
  /** UUID assigned at creation — used as the stable edit/delete key. */
  id: string;
  name: string;
  icon: GoalIcon;
  /** Dollar target for the goal. */
  targetAmount: number;
  /** Calendar year the user wants to reach the goal. */
  targetYear: number;
  /** Amount already saved toward this goal when it was set up. Default 0. */
  startingValue: number;
  /**
   * Amount the user saves toward this goal each PAYCHECK, outside of FRED.
   * The monthly equivalent is derived live from the user's investment-schedule
   * frequency via InvestmentFrequencyUtils.toMonthlyEquivalent — not stored.
   */
  perPaycheckOutsideFRED: number;
  /** Marked as completed by the user. */
  completed: boolean;
}

export interface ProfilePrefs {
  rebalanceSchedule: RebalanceSchedule;
}

// ─── Defaults ────────────────────────────────────────────────────────────────

const DEFAULT_PREFS: ProfilePrefs = {
  rebalanceSchedule: 'Quarterly',
};

// ─── Service ─────────────────────────────────────────────────────────────────

/**
 * GoalsService — FRED-216
 *
 * Per-user localStorage CRUD for:
 *   - goals[]          : projection-only goals (not managed by FRED's investment engine)
 *   - profilePrefs     : rebalance schedule preference
 *
 * V1 persistence decision (documented in implementation-notes-FRED-216.md):
 * These are UI/projection features. The rebalancing engine and server-side goal
 * tracking are future stories; localStorage is the agreed interim store.
 *
 * Discipline mirrors SimulationHistoryService:
 *   - Key prefix includes schema version
 *   - Per-user scoping via localStorage 'userId'
 *   - Corrupt/stale data silently returns empty/default state
 *   - Write failures (private mode, quota) are silently swallowed
 */
@Injectable({ providedIn: 'root' })
export class GoalsService {

  // ── Goals CRUD ─────────────────────────────────────────────────────────────

  /** Returns goals for the current user, or [] on any error. */
  getGoals(userId: string): Goal[] {
    if (!userId) return [];
    try {
      const raw = localStorage.getItem(KEY_GOALS_PREFIX + userId);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(g => this._isValidGoal(g));
    } catch {
      return [];
    }
  }

  /** Append a new goal and persist. Generates a stable UUID for the entry. */
  addGoal(userId: string, goal: Omit<Goal, 'id'>): Goal {
    const entry: Goal = { ...goal, id: this._uuid() };
    const existing = this.getGoals(userId);
    this._saveGoals(userId, [...existing, entry]);
    return entry;
  }

  /** Replace an existing goal matched by id. No-ops if the id is not found. */
  updateGoal(userId: string, updated: Goal): void {
    const existing = this.getGoals(userId);
    const next = existing.map(g => g.id === updated.id ? updated : g);
    this._saveGoals(userId, next);
  }

  /** Remove a goal by id. No-ops if the id is not found. */
  deleteGoal(userId: string, goalId: string): void {
    const existing = this.getGoals(userId);
    this._saveGoals(userId, existing.filter(g => g.id !== goalId));
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
      // Storage unavailable — silently swallow.
    }
  }

  // ── Private ────────────────────────────────────────────────────────────────

  private _saveGoals(userId: string, goals: Goal[]): void {
    if (!userId) return;
    try {
      localStorage.setItem(KEY_GOALS_PREFIX + userId, JSON.stringify(goals));
    } catch {
      // Storage unavailable — silently swallow.
    }
  }

  private _isValidGoal(g: any): g is Goal {
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

  /** Minimal UUID v4-like string without crypto dependency. Sufficient for localStorage keys. */
  private _uuid(): string {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
      const r = Math.random() * 16 | 0;
      return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
  }
}
