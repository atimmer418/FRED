// Shared Goals v3 types. Plain types only: no Angular imports, so pure utils can use them.

export type GoalType = 'KIDS' | 'HOUSE' | 'VACATION' | 'CAR' | 'EMERGENCY_FUND' | 'WEDDING' | 'CUSTOM';

export type GoalIcon =
  | 'home'
  | 'health_and_safety'
  | 'flag'
  | 'balance'
  | 'savings'
  | 'school'
  | 'directions_car'
  | 'child_care'
  | 'flight'
  | 'favorite';

export type RateKind = 'market' | 'cash';

/** A Saved so far correction: Missed one, Doubled up, or an exact figure. */
export type LedgerKind = 'missed' | 'extra' | 'set';

/** The kids account a saving unit tracks. */
export type KidUnitAccount = 'plan529' | 'brokerage' | 'firstYear';

/** The server's key for one kid account's tally: `kid:0:plan529`. */
export function unitKey(kid: number, account: KidUnitAccount): string {
  return `kid:${kid}:${account}`;
}

/**
 * One saving unit's "Saved so far" figures, computed by the server on every read. Dates are local
 * `yyyy-MM-dd` strings: read them with utils/local-date.ts, never `new Date(str)`.
 */
export interface UnitTally {
  tally: number;
  creditedThrough: string | null;
  creditsUntil: string | null;
  nextCreditDate: string | null;
  nextCreditAmount: number;
  /** Doubled up minus Missed one since the later of the plan anchor and the last exact figure. */
  net: number;
  /** When net is negative, the oldest missed payday not yet made up. */
  netSince: string | null;
  startedCrediting: boolean;
}

export interface AccountState {
  /** The unit's tally, owned by the server. */
  balance: number;
  balanceUpdatedAt: string | null;
  anchorBalance: number;
  anchorMonthly: number;
  /** Optional on the wire: accounts written before the tally have none. */
  creditedThrough?: string | null;
  creditsUntil?: string | null;
  target?: number | null;
}

export interface KidInput {
  label: string;
  birthYear: number | null;
  birthMonth: number | null;
  /** The first-year fund target, snapshotted when the fund was added. Missing and null both mean no fund. */
  firstYearTarget?: number | null;
  /** The fund's stored end month (year and month 1..12). */
  firstYearDueYear?: number | null;
  firstYearDueMonth?: number | null;
  accounts: { brokerage: AccountState; plan529: AccountState; firstYear?: AccountState | null };
}

export interface KidsConfig {
  brokerageTarget: number;
  plan529Target: number;
  kids: KidInput[];
  /** Whether the family plans a first-year fund for kids not yet born. Missing and null both mean off. */
  firstYearFund?: boolean | null;
  /** Gift days: $1,000 per kid every January, monthly solved net of it. Missing and null both mean off. */
  giftDays?: boolean | null;
}

export interface HouseCarConfig {
  price: number;
  downPaymentPct: number | null;
  targetYear: number;
  /** Optional on the wire: goals written before loan terms have none. */
  loanRate?: number | null;
  downPaymentAmount?: number | null;
}

export interface VacationConfig {
  /** 1 to 10. */
  tripsPerYear: number;
  budgetPerTrip: number;
  savedThisYear: number;
  savedYear: number;
}

export interface EmergencyFundConfig {
  monthlyExpenses: number;
  months: 3 | 6;
  targetYear: number;
}

export interface TargetConfig {
  targetAmount: number;
  targetYear: number;
}

export type GoalConfig = KidsConfig | HouseCarConfig | VacationConfig | EmergencyFundConfig | TargetConfig;

export interface Goal {
  id: number;
  clientId: string | null;
  type: GoalType;
  name: string;
  icon: GoalIcon;
  config: GoalConfig;
  currentBalance: number;
  balanceUpdatedAt: string | null;
  anchorBalance: number;
  anchorMonthly: number;
  planRate: number;
  planRateKind: RateKind;
  planAnchoredAt: string;
  completed: boolean;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
  /** Simple and vacation goals: the Saved so far figures. Null for kids and before the first read. */
  tally?: UnitTally | null;
  /** Kids only: each account's figures, keyed by unitKey. */
  unitTallies?: Record<string, UnitTally> | null;
  target?: number | null;
}

export interface GoalWrite {
  type: GoalType;
  name: string;
  icon: GoalIcon;
  config: GoalConfig;
  currentBalance: number;
  completed: boolean;
  anchorMonthly: number;
  planRate: number;
  planRateKind: RateKind;
  /** The last day paydays credit (yyyy-MM-dd); null for vacation. Written whenever the plan anchors. */
  creditsUntil?: string | null;
  /** The unit's savings target as the client computed it; null when the goal has none. */
  target?: number | null;
}

export interface LegacyGoalV2 {
  id: string;
  name: string;
  icon: string;
  targetAmount: number;
  targetYear: number;
  startingValue: number;
  perPaycheckOutsideFRED: number;
  completed: boolean;
}
