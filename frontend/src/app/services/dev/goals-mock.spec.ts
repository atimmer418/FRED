import { ACTIVE_GOALS_MOCK, buildMockGoals, mockGoals } from './goals-mock';
import { GoalType, HouseCarConfig, KidsConfig, VacationConfig } from '../../models/goal.model';
import { horizonMonths } from '../../utils/goal-plan.utils';
import { buildGoalPlanView } from '../../pages/goal/goal-plan.view-model';

describe('goals-mock (dev)', () => {
  const now = new Date(2026, 10, 4, 12, 0, 0);
  const ALL_TYPES: GoalType[] = ['KIDS', 'HOUSE', 'CAR', 'VACATION', 'EMERGENCY_FUND', 'WEDDING', 'CUSTOM'];

  it('is null under karma regardless of the toggle', () => {
    expect(ACTIVE_GOALS_MOCK).toBeNull();
  });

  it('produces one goal per type for one_of_each', () => {
    const goals = buildMockGoals('one_of_each', now);
    expect(goals.map(g => g.type).sort()).toEqual([...ALL_TYPES].sort());
    expect(goals.every(g => g.id > 0 && g.clientId === null)).toBeTrue();
    expect(new Set(goals.map(g => g.id)).size).toBe(goals.length);
  });

  it('produces only the kids goal for kids_only', () => {
    const goals = buildMockGoals('kids_only', now);
    expect(goals.length).toBe(1);
    expect(goals[0].type).toBe('KIDS');
  });

  it('uses ISO timestamps without Z and a cash rate for the emergency fund', () => {
    const goals = buildMockGoals('one_of_each', now);
    for (const g of goals) {
      expect(g.planAnchoredAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/);
    }
    const ef = goals.find(g => g.type === 'EMERGENCY_FUND')!;
    expect(ef.planRateKind).toBe('cash');
    expect(ef.planRate).toBe(0.04);
  });

  it('gives the undecided kid an invested first-year fund due 36 months after the anchor', () => {
    const kids = buildMockGoals('kids_only', now)[0];
    const cfg = kids.config as KidsConfig;
    expect(cfg.firstYearFund).toBeTrue();
    const kid = cfg.kids.find(k => k.birthYear === null)!;
    expect(kid.firstYearTarget).toBe(20800);
    expect(kid.accounts.firstYear).toBeTruthy();
    // anchored 4 months before now (Jul 2026), so due Jul 2029: 32 months after Nov 2026
    expect(horizonMonths(now, kid.firstYearDueYear!, kid.firstYearDueMonth!)).toBe(32);
    expect(cfg.kids.filter(k => k.accounts.firstYear).length).toBe(1);
  });

  it('gives down payments as whole percents', () => {
    const goals = buildMockGoals('one_of_each', now);
    const house = goals.find(g => g.type === 'HOUSE')!.config as HouseCarConfig;
    const car = goals.find(g => g.type === 'CAR')!.config as HouseCarConfig;
    expect([house.downPaymentPct, car.downPaymentPct]).toEqual([20, 25]);
  });

  it('one_of_each still yields started, on_plan, ahead, behind and done through buildGoalPlanView', () => {
    const goals = buildMockGoals('one_of_each', now);
    const kinds = new Set(goals.map(g => buildGoalPlanView(g, {
      marketRate: 0.10, frequency: 'biweekly', now, rateKindOverride: g.planRateKind,
    }).status.kind));
    for (const kind of ['started', 'on_plan', 'ahead', 'behind', 'done'] as const) {
      expect(kinds.has(kind)).withContext(kind).toBeTrue();
    }
  });

  it('turns gift days on for the kids goal, and the family can take them, so the Next up row shows', () => {
    const kids = buildMockGoals('kids_only', now)[0];
    const cfg = kids.config as KidsConfig;
    expect(cfg.giftDays).toBeTrue();
    expect(cfg.kids.length).toBeLessThanOrEqual(5);

    const view = buildGoalPlanView(kids, {
      marketRate: 0.10, frequency: 'biweekly', now, rateKindOverride: null,
    });
    expect(view.giftDays?.available).toBeTrue();
    expect(view.giftDays?.on).toBeTrue();
  });

  it('keeps vacation trips within the quiz range', () => {
    const vacation = buildMockGoals('one_of_each', now).find(g => g.type === 'VACATION')!;
    const trips = (vacation.config as VacationConfig).tripsPerYear;
    expect(trips).toBeGreaterThanOrEqual(1);
    expect(trips).toBeLessThanOrEqual(4);
  });

  it('mockGoals returns an empty list while the toggle is off', () => {
    expect(mockGoals(now)).toEqual([]);
  });
});
