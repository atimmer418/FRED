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

export interface AccountState {
  balance: number;
  balanceUpdatedAt: string | null;
  anchorBalance: number;
  anchorMonthly: number;
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
