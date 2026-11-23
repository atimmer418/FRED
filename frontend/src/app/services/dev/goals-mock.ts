/**
 * DEV-ONLY goals mock.
 *
 * Goals now live on the backend (/api/goals), so eyeballing every goal type and every
 * status chip (Started, On plan, Ahead, Behind, Done) normally means creating goals by hand.
 * This module lets you pick a canned goal set on the local build instead, exactly like
 * MOCK_CHOICES in tier-mock.ts.
 *
 * Usage: uncomment ONE entry in MOCK_CHOICES below. Leave them all commented for the real
 * server goals. GoalsService only consults this when environment.local is true (the local
 * dev loop); the deployed dev/test/prod builds all have local:false, so it can never leak
 * there. Karma additionally forces it null so specs that assert on the exact HTTP traffic
 * of load() are immune to the toggle state.
 *
 * It is a READ overlay only: load() and refresh() return these goals with no HTTP request.
 * Writes (create, update, remove, importLegacy) still go to the real backend.
 */
import { AccountState, Goal, KidInput, UnitTally, unitKey } from '../../models/goal.model';
import { firstYearDue } from '../../utils/goal-plan.utils';
import { addDays, fromLocalDate } from '../../utils/local-date';

export type MockGoalsChoice = 'one_of_each' | 'kids_only';

/**
 * DEV TOGGLE: uncomment ONE line to force that goal set; all commented = real server goals.
 * The first uncommented entry wins.
 */
const MOCK_CHOICES: MockGoalsChoice[] = [
  // 'one_of_each',  // one goal of every type, mixed status chips
  // 'kids_only',    // just the Kids goal (two kids, one undecided)
];

/** Forced null under karma regardless of the toggle (see tier-mock.ts for the rationale). */
const IN_KARMA = typeof window !== 'undefined' && !!(window as any).__karma__;

export const ACTIVE_GOALS_MOCK: MockGoalsChoice | null =
  IN_KARMA ? null : (MOCK_CHOICES.find(Boolean) ?? null);

/** The goals for the active toggle at `now`; [] when the toggle is off. */
export function mockGoals(now: Date): Goal[] {
  return ACTIVE_GOALS_MOCK ? buildMockGoals(ACTIVE_GOALS_MOCK, now) : [];
}

/** Pure builder, so specs can exercise it without the toggle. */
export function buildMockGoals(choice: MockGoalsChoice, now: Date): Goal[] {
  const year = now.getFullYear();
  const anchoredAt = iso(addMonths(now, -4));
  const updatedAt = iso(addMonths(now, -1));
  const createdAt = iso(addMonths(now, -5));

  const base = {
    clientId: null,
    planAnchoredAt: anchoredAt,
    completed: false,
    createdAt,
    updatedAt: iso(now),
  };

  const account = (balance: number, monthly: number, anchor: number, updated: string | null): AccountState => ({
    balance,
    balanceUpdatedAt: updated,
    anchorBalance: anchor,
    anchorMonthly: monthly,
  });

  const fundDue = firstYearDue(null, null, addMonths(now, -4));

  // The server's Saved so far figures. Status reads the net of taps: 0 on plan, + ahead, - behind.
  const today = fromLocalDate(now);
  const tally = (amount: number, share: number, extra: Partial<UnitTally> = {}): UnitTally => ({
    tally: amount,
    creditedThrough: addDays(today, -6),
    creditsUntil: null,
    nextCreditDate: addDays(today, 8),
    nextCreditAmount: share,
    net: 0,
    netSince: null,
    startedCrediting: true,
    ...extra,
  });

  const kids: KidInput[] = [
    {
      label: 'Maya',
      birthYear: year - 3,
      birthMonth: 5,
      accounts: {
        brokerage: account(2400, 150, 1800, updatedAt),
        plan529: account(3100, 200, 2200, updatedAt),
      },
    },
    {
      label: 'Second kid',
      birthYear: year + 1,
      birthMonth: 3,
      // Not yet born (expected next March): a first-year fund, due 36 months after the plan was anchored.
      firstYearTarget: 20800,
      firstYearDueYear: fundDue.year,
      firstYearDueMonth: fundDue.month,
      accounts: {
        brokerage: account(0, 100, 0, null),
        plan529: account(0, 100, 0, null),
        firstYear: account(0, 500, 0, null),
      },
    },
  ];

  const kidsGoal: Goal = {
    ...base,
    id: 9001,
    type: 'KIDS',
    name: 'Kids',
    icon: 'child_care',
    // giftDays stays true here on purpose: the plan page ignores the stored flag (monthly only).
    config: { brokerageTarget: 100000, plan529Target: 80000, kids, firstYearFund: true, giftDays: true },
    currentBalance: 5500,
    balanceUpdatedAt: updatedAt,
    anchorBalance: 4000,
    anchorMonthly: 550,
    planRate: 0.07,
    planRateKind: 'market',
    sortOrder: 0,
    unitTallies: {
      [unitKey(0, 'brokerage')]: tally(2400, 69),
      [unitKey(0, 'plan529')]: tally(3100, 92),
      [unitKey(1, 'brokerage')]: tally(0, 46, { startedCrediting: false, creditedThrough: null }),
      [unitKey(1, 'plan529')]: tally(0, 46, { startedCrediting: false, creditedThrough: null }),
      [unitKey(1, 'firstYear')]: tally(0, 231, { startedCrediting: false, creditedThrough: null }),
    },
  };

  if (choice === 'kids_only') return [kidsGoal];

  return [
    kidsGoal,
    {
      // No payday has credited yet: reads as Started.
      ...base,
      id: 9002,
      type: 'HOUSE',
      name: 'House',
      icon: 'home',
      config: { price: 450000, downPaymentPct: 20, targetYear: year + 6 },
      currentBalance: 0,
      balanceUpdatedAt: null,
      anchorBalance: 0,
      anchorMonthly: 1200,
      planRate: 0.07,
      planRateKind: 'market',
      sortOrder: 1,
      tally: tally(0, 554, { startedCrediting: false, creditedThrough: null }),
    },
    {
      // A Doubled up: Ahead.
      ...base,
      id: 9003,
      type: 'CAR',
      name: 'Car',
      icon: 'directions_car',
      config: { price: 32000, downPaymentPct: 25, targetYear: year + 3 },
      currentBalance: 4200,
      balanceUpdatedAt: updatedAt,
      anchorBalance: 1000,
      anchorMonthly: 400,
      planRate: 0.07,
      planRateKind: 'market',
      sortOrder: 2,
      tally: tally(4200, 185, { net: 185 }),
    },
    {
      ...base,
      id: 9004,
      type: 'VACATION',
      name: 'Vacation',
      icon: 'flight',
      config: { tripsPerYear: 2, budgetPerTrip: 2500, savedThisYear: 1800, savedYear: year },
      currentBalance: 1800,
      balanceUpdatedAt: updatedAt,
      anchorBalance: 600,
      anchorMonthly: 300,
      planRate: 0.04,
      planRateKind: 'cash',
      sortOrder: 3,
      tally: tally(1800, 138),
    },
    {
      // A Missed one: Behind.
      ...base,
      id: 9005,
      type: 'EMERGENCY_FUND',
      name: 'Emergency fund',
      icon: 'health_and_safety',
      config: { monthlyExpenses: 3800, months: 6, targetYear: year + 2 },
      currentBalance: 3000,
      balanceUpdatedAt: updatedAt,
      anchorBalance: 2500,
      anchorMonthly: 900,
      planRate: 0.04,
      planRateKind: 'cash',
      sortOrder: 4,
      tally: tally(3000, 415, { net: -415, netSince: addDays(today, -20) }),
    },
    {
      // Nothing missed: On plan.
      ...base,
      id: 9006,
      type: 'WEDDING',
      name: 'Wedding',
      icon: 'favorite',
      config: { targetAmount: 28000, targetYear: year + 2 },
      currentBalance: 8300,
      balanceUpdatedAt: updatedAt,
      anchorBalance: 6000,
      anchorMonthly: 750,
      planRate: 0.04,
      planRateKind: 'cash',
      sortOrder: 5,
      tally: tally(8300, 346),
    },
    {
      // Marked complete: Done.
      ...base,
      id: 9007,
      type: 'CUSTOM',
      name: 'New laptop',
      icon: 'flag',
      config: { targetAmount: 2400, targetYear: year },
      currentBalance: 2400,
      balanceUpdatedAt: updatedAt,
      anchorBalance: 1200,
      anchorMonthly: 300,
      planRate: 0.04,
      planRateKind: 'cash',
      completed: true,
      sortOrder: 6,
      tally: tally(2400, 138, { nextCreditDate: null, nextCreditAmount: 0 }),
    },
  ];
}

function addMonths(d: Date, n: number): Date {
  const r = new Date(d.getTime());
  r.setMonth(r.getMonth() + n);
  return r;
}

/** yyyy-MM-ddTHH:mm:ss in local time, no zone suffix (matches the backend LocalDateTime wire form). */
function iso(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
