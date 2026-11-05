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
import { AccountState, Goal, KidInput } from '../../models/goal.model';
import { firstYearDue } from '../../utils/goal-plan.utils';

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
      birthYear: null,
      birthMonth: null,
      // Not yet born: an invested first-year fund, due 36 months after the plan was anchored.
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
    config: { brokerageTarget: 50000, plan529Target: 80000, kids, firstYearFund: true },
    currentBalance: 5500,
    balanceUpdatedAt: updatedAt,
    anchorBalance: 4000,
    anchorMonthly: 550,
    planRate: 0.07,
    planRateKind: 'market',
    sortOrder: 0,
  };

  if (choice === 'kids_only') return [kidsGoal];

  return [
    kidsGoal,
    {
      // Balance never updated: reads as Started.
      ...base,
      id: 9002,
      type: 'HOUSE',
      name: 'House',
      icon: 'home',
      config: { price: 450000, downPaymentPct: 0.2, targetYear: year + 6 },
      currentBalance: 0,
      balanceUpdatedAt: null,
      anchorBalance: 0,
      anchorMonthly: 1200,
      planRate: 0.07,
      planRateKind: 'market',
      sortOrder: 1,
    },
    {
      // Balance well above the plan path: Ahead.
      ...base,
      id: 9003,
      type: 'CAR',
      name: 'Car',
      icon: 'directions_car',
      config: { price: 32000, downPaymentPct: 0.25, targetYear: year + 3 },
      currentBalance: 4200,
      balanceUpdatedAt: updatedAt,
      anchorBalance: 1000,
      anchorMonthly: 400,
      planRate: 0.07,
      planRateKind: 'market',
      sortOrder: 2,
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
    },
    {
      // Balance below the plan path: Behind.
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
    },
    {
      // Balance close to the plan path: On plan.
      ...base,
      id: 9006,
      type: 'WEDDING',
      name: 'Wedding',
      icon: 'favorite',
      config: { targetAmount: 28000, targetYear: year + 2 },
      currentBalance: 9100,
      balanceUpdatedAt: updatedAt,
      anchorBalance: 6000,
      anchorMonthly: 750,
      planRate: 0.04,
      planRateKind: 'cash',
      sortOrder: 5,
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
