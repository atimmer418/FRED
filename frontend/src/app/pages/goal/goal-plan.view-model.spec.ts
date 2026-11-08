import { buildGoalPlanView, fmtCompact, fmtMoney, GoalPlanContext, GoalPlanInput } from './goal-plan.view-model';
import { buildKidTimeline } from './kid-timeline.model';
import { AccountState, KidInput, KidsConfig } from '../../models/goal.model';
import { GIFT_DAY_LUMP, futureValue, proposeKidAccountPlan } from '../../utils/goal-plan.utils';
import { InvestmentFrequencyUtils } from '../../utils/investment-frequency.utils';
import { CASH_RATE } from '../../services/rate-assumptions.service';

const NOW = new Date(2026, 10, 15); // 15 Nov 2026
const Y = NOW.getFullYear();

function account(balance = 0, extra: Partial<AccountState> = {}): AccountState {
  return { balance, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0, ...extra };
}

function base(partial: Partial<GoalPlanInput>): GoalPlanInput {
  return {
    type: 'CUSTOM',
    name: 'Boat fund',
    config: { targetAmount: 200_000, targetYear: Y + 6 },
    currentBalance: 0,
    completed: false,
    clientId: null,
    anchorBalance: 0,
    anchorMonthly: 0,
    planRate: 0.10,
    planRateKind: 'market',
    planAnchoredAt: '2026-01-01T00:00:00',
    balanceUpdatedAt: null,
    ...partial,
  };
}

const ctx = (extra: Partial<GoalPlanContext> = {}): GoalPlanContext => ({
  marketRate: 0.10,
  frequency: 'biweekly',
  now: NOW,
  rateKindOverride: null,
  ...extra,
});

function kidsGoal(kids: KidsConfig['kids'], extra: Partial<GoalPlanInput> = {}): GoalPlanInput {
  const config: KidsConfig = { plan529Target: 100_000, brokerageTarget: 250_000, kids };
  return base({ type: 'KIDS', config, ...extra });
}

describe('buildGoalPlanView', () => {
  it('aggregates kids accounts into one status and progress', () => {
    const goal = kidsGoal([
      {
        label: 'Ada', birthYear: Y - 2, birthMonth: 3,
        accounts: {
          plan529: account(10_000, { balanceUpdatedAt: '2026-06-01T00:00:00', anchorBalance: 8_000, anchorMonthly: 200 }),
          brokerage: account(20_000, { balanceUpdatedAt: '2026-09-01T00:00:00', anchorBalance: 15_000, anchorMonthly: 300 }),
        },
      },
      {
        label: 'Ben', birthYear: Y - 5, birthMonth: 8,
        accounts: { plan529: account(5_000), brokerage: account(0) },
      },
    ]);
    const view = buildGoalPlanView(goal, ctx());

    expect(view.kids?.length).toBe(2);
    const p529 = view.kids!.find(k => k.account === 'plan529')!;
    expect(p529.label).toBe('529 plans');
    expect(p529.perKid.length).toBe(2);
    expect(p529.perKid[0].balance).toBe(10_000);
    expect(p529.totals.monthly).toBe(p529.perKid[0].plan.monthly + p529.perKid[1].plan.monthly);
    const brokerage = view.kids!.find(k => k.account === 'brokerage')!;
    expect(view.monthlyTotal).toBe(p529.totals.monthly + brokerage.totals.monthly);

    // balance 35K of a 2 × 350K family target
    expect(view.progressPct).toBeCloseTo((35_000 / 700_000) * 100, 6);
    // graded at the latest account log, as one family plan
    expect(['ahead', 'on_plan', 'behind']).toContain(view.status.kind);
    expect(view.status.asOf).toBe('2026-09-01T00:00:00');
  });

  it('grades status with the stored plan rate, not the live chip', () => {
    const goal = base({
      currentBalance: 30_000,
      anchorBalance: 20_000,
      anchorMonthly: 1_000,
      planRate: 0.10,
      balanceUpdatedAt: '2026-10-01T00:00:00',
    });
    const at8 = buildGoalPlanView(goal, ctx({ marketRate: 0.08 }));
    const at12 = buildGoalPlanView(goal, ctx({ marketRate: 0.12 }));

    expect(at8.monthlyTotal).not.toBe(at12.monthlyTotal); // the live plan moves
    expect(at12.status).toEqual(at8.status);              // the status does not
  });

  it('reads imported for a never-planned import', () => {
    const view = buildGoalPlanView(base({ clientId: 'v2-abc', anchorMonthly: 0, balanceUpdatedAt: '2026-11-01T00:00:00', currentBalance: 500 }), ctx());
    expect(view.status.kind).toBe('imported');
    expect(view.subtitle).toBe('Plan updated, take a look');

    const planned = buildGoalPlanView(base({ clientId: 'v2-abc', anchorMonthly: 900 }), ctx());
    expect(planned.status.kind).not.toBe('imported');
  });

  it('builds each documented subtitle shape and never emits an em dash', () => {
    const decided = kidsGoal([
      { label: 'A', birthYear: Y - 4, birthMonth: 2, accounts: { plan529: account(), brokerage: account() } },
      { label: 'B', birthYear: Y - 2, birthMonth: 5, accounts: { plan529: account(), brokerage: account() } },
      { label: 'C', birthYear: Y, birthMonth: 1, accounts: { plan529: account(), brokerage: account() } },
    ]);
    const kidsView = buildGoalPlanView(decided, ctx());
    expect(kidsView.subtitle).toMatch(/^\$[\d,]+\/mo for all 3 kids$/);

    const undecided = kidsGoal([
      { label: 'A', birthYear: null, birthMonth: null, accounts: { plan529: account(), brokerage: account() } },
      { label: 'B', birthYear: Y + 2, birthMonth: 4, accounts: { plan529: account(), brokerage: account() } },
    ]);
    const relView = buildGoalPlanView(undecided, ctx());
    expect(relView.subtitle).toMatch(/^\$[\d,]+\/mo for both kids$/);
    expect(relView.relative).toBeTrue();
    expect(relView.kids![0].perKid.every(k => k.relative)).toBeTrue();

    const vacation = buildGoalPlanView(base({
      type: 'VACATION',
      config: { tripsPerYear: 4, budgetPerTrip: 2_500, savedThisYear: 0, savedYear: Y },
    }), ctx());
    expect(vacation.subtitle).toBe('$834/mo for 4 trips a year');
    expect(vacation.vacation?.monthly).toBe(834);

    const house = buildGoalPlanView(base({
      type: 'HOUSE',
      config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6 },
    }), ctx());
    expect(house.subtitle).toMatch(/^\$[\d,]+\/mo for your home$/);

    const car = buildGoalPlanView(base({
      type: 'CAR',
      config: { price: 40_000, downPaymentPct: null, targetYear: Y + 4 },
    }), ctx());
    expect(car.subtitle).toMatch(/^\$[\d,]+\/mo for your car$/);

    const custom = buildGoalPlanView(base({}), ctx());
    expect(custom.subtitle).toMatch(/^\$[\d,]+\/mo Boat fund$/);
    expect(custom.headline).toBe(`Reaches $200K in Dec ${Y + 6}`);

    const shortKid = kidsGoal([
      { label: 'Teen', birthYear: Y - 17, birthMonth: 12, accounts: { plan529: account(), brokerage: account() } },
    ]);

    const all = [kidsView, relView, vacation, house, car, custom, buildGoalPlanView(shortKid, ctx())];
    for (const v of all) {
      const text = [v.headline, v.subtitle, v.heroLabel, v.audience, v.howFredHelps].join(' ');
      expect(text).not.toContain('\u2014');
    }

    expect(fmtCompact(372_338.52)).toBe('$372K');
    expect(fmtCompact(1_180_000)).toBe('$1.18M');
  });

  it('grades KIDS status the same at any chip rate', () => {
    const goal = kidsGoal([
      {
        label: 'Ada', birthYear: Y - 2, birthMonth: 3,
        accounts: {
          plan529: account(9_000, { balanceUpdatedAt: '2026-09-01T00:00:00', anchorBalance: 5_000, anchorMonthly: 250 }),
          brokerage: account(14_000, { balanceUpdatedAt: '2026-09-01T00:00:00', anchorBalance: 10_000, anchorMonthly: 400 }),
        },
      },
    ]);
    const at8 = buildGoalPlanView(goal, ctx({ marketRate: 0.08 }));
    const at12 = buildGoalPlanView(goal, ctx({ marketRate: 0.12 }));
    expect(at8.monthlyTotal).not.toBe(at12.monthlyTotal);
    expect(at12.status).toEqual(at8.status);
  });

  it('reads on_plan when a kid account matches the anchor-time plan, January gift lump included', () => {
    const anchoredAt = '2025-11-01T00:00:00';
    const loggedAt = '2026-02-01T00:00:00'; // 3 months after the anchor
    const anchorPlan = proposeKidAccountPlan({
      target: 100_000, balance: 0, birthYear: Y - 3, birthMonth: 3,
      marketRate: 0.10, rateKindOverride: 'market', now: new Date(anchoredAt), giftDays: true,
    });
    const lumpsSoFar = anchorPlan.lumps.filter(l => l.monthIndex <= 3);
    expect(lumpsSoFar).withContext('one January gift lump inside the window').toEqual([{ monthIndex: 2, amount: 1000 }]);
    const expected = futureValue(0, anchorPlan.monthly, 3, 0.10, lumpsSoFar);

    const config: KidsConfig = {
      plan529Target: 100_000,
      brokerageTarget: 0,
      giftDays: true,
      kids: [{
        label: 'Ada', birthYear: Y - 3, birthMonth: 3,
        accounts: {
          plan529: account(expected, { balanceUpdatedAt: loggedAt, anchorBalance: 0, anchorMonthly: anchorPlan.monthly }),
          brokerage: account(0),
        },
      }],
    };
    const view = buildGoalPlanView(base({ type: 'KIDS', config, planAnchoredAt: anchoredAt }), ctx({ marketRate: 0.12 }));
    expect(view.status.kind).toBe('on_plan');
    expect(view.status.delta).toBeCloseTo(0, 6);
  });

  it('resolves a rate kind per kid account from the anchor date', () => {
    const goal = kidsGoal([
      { label: 'Teen', birthYear: 2010, birthMonth: 6, accounts: { plan529: account(), brokerage: account() } },
      { label: 'Baby', birthYear: null, birthMonth: null, accounts: { plan529: account(), brokerage: account() } },
    ], { planAnchoredAt: NOW.toISOString() });
    const view = buildGoalPlanView(goal, ctx({ marketRate: 0.12 }));
    for (const acct of view.kids!) {
      expect(acct.perKid[0].plan.rateKind).toBe('cash');
      expect(acct.perKid[0].plan.rate).toBe(CASH_RATE);
      expect(acct.perKid[1].plan.rateKind).toBe('market');
      expect(acct.perKid[1].plan.rate).toBe(0.12);
    }
    expect(view.rateKind).toBe('market');

    // A stored cash kind or a rate-kind override no longer decides a kids goal: every account
    // resolves from its own distance at the anchor.
    const cash = buildGoalPlanView(
      { ...goal, planRateKind: 'cash', planRate: CASH_RATE },
      ctx({ marketRate: 0.12, rateKindOverride: 'cash' }),
    );
    for (const acct of cash.kids!) {
      expect(acct.perKid[0].plan.rateKind).toBe('cash');
      expect(acct.perKid[1].plan.rateKind).toBe('market');
    }
    expect(cash.rateKind).toBe('market');

    // Cash overall only when every account plan is under 36 months at the anchor.
    const teenOnly = kidsGoal([
      { label: 'Teen', birthYear: 2010, birthMonth: 6, accounts: { plan529: account(), brokerage: account() } },
    ], { planAnchoredAt: NOW.toISOString() });
    expect(buildGoalPlanView(teenOnly, ctx()).rateKind).toBe('cash');
  });

  it('hides per paycheck without a frequency', () => {
    expect(buildGoalPlanView(base({}), ctx({ frequency: '' })).perPaycheck).toBeNull();
    expect(buildGoalPlanView(base({}), ctx({ frequency: 'biweekly' })).perPaycheck).toBeGreaterThan(0);
  });
});

// ── First-year fund and kid costs ────────────────────────────────────────────

function fundKid(
  label: string,
  birthYear: number | null,
  birthMonth: number | null,
  due: [number, number],
  target: number,
  fund: AccountState = account(),
  plan529: AccountState = account(),
): KidInput {
  return {
    label, birthYear, birthMonth,
    firstYearTarget: target,
    firstYearDueYear: due[0],
    firstYearDueMonth: due[1],
    accounts: { plan529, brokerage: account(), firstYear: fund },
  };
}

function fundGoal(
  kids: KidInput[],
  targets: { plan529Target: number; brokerageTarget: number },
  extra: Partial<GoalPlanInput> = {},
): GoalPlanInput {
  const config: KidsConfig = { ...targets, kids, firstYearFund: true };
  return base({ type: 'KIDS', config, planAnchoredAt: '2026-11-15T00:00:00', ...extra });
}

describe('buildGoalPlanView first-year fund', () => {
  const noTargets = { plan529Target: 0, brokerageTarget: 0 };

  it('has no first-year block when no kid holds a fund', () => {
    const three = kidsGoal([0, 1, 2].map(i => ({
      label: `K${i}`, birthYear: Y - 3 - i, birthMonth: 4, accounts: { plan529: account(), brokerage: account() },
    })));
    const view = buildGoalPlanView(three, ctx());
    expect(view.firstYear).toBeUndefined();
  });

  it('adds unfinished first-year plans to the monthly total, one row per kid with a fund', () => {
    const goal = fundGoal([
      fundKid('A', null, null, [2029, 11], 20800),
      fundKid('B', Y + 1, 5, [2027, 5], 25700),
      { label: 'C', birthYear: Y - 4, birthMonth: 2, accounts: { plan529: account(), brokerage: account() } },
    ], { plan529Target: 100_000, brokerageTarget: 250_000 });
    const view = buildGoalPlanView(goal, ctx());
    const accountsTotal = view.kids!.reduce((sum, a) => sum + a.totals.monthly, 0);
    expect(view.firstYear!.rows.length).toBe(2);
    expect(view.firstYear!.rows.map(r => r.kidIndex)).toEqual([0, 1]);
    expect(view.firstYear!.rows[0].plan.monthly).toBe(498);
    expect(view.firstYear!.rows[1].plan.rateKind).toBe('cash');
    expect(view.firstYear!.totalMonthly).toBe(view.firstYear!.rows[0].plan.monthly + view.firstYear!.rows[1].plan.monthly);
    expect(view.monthlyTotal).toBe(accountsTotal + view.firstYear!.totalMonthly);
  });

  it('counts a fund down from its stored due date when viewed six months later', () => {
    const goal = fundGoal([fundKid('A', null, null, [2029, 5], 25700)], noTargets, { planAnchoredAt: '2026-05-15T10:00:00' });
    const view = buildGoalPlanView(goal, ctx());
    expect(view.firstYear!.rows[0].plan.n).toBe(30);
    expect(view.firstYear!.rows[0].plan.rateKind).toBe('market');
  });

  it('keeps a fresh undecided fund invested and a 24-month fund in cash', () => {
    const goal = fundGoal([
      fundKid('A', null, null, [2029, 11], 20800),
      fundKid('B', 2028, 11, [2028, 11], 20800),
    ], noTargets);
    const rows = buildGoalPlanView(goal, ctx()).firstYear!.rows;
    expect(rows[0].plan.rateKind).toBe('market');
    expect(rows[0].plan.rate).toBe(0.10);
    expect(rows[1].plan.rateKind).toBe('cash');
    expect(rows[1].plan.rate).toBe(CASH_RATE);
  });

  it('re-evaluates kinds at now when a re-anchor is pending', () => {
    // Anchored May 2026 the 2029-05 fund was 36 months out (market); from now it is 30 (cash).
    const goal = fundGoal([fundKid('A', null, null, [2029, 5], 25700)], noTargets, { planAnchoredAt: '2026-05-15T10:00:00' });
    expect(buildGoalPlanView(goal, ctx()).firstYear!.rows[0].plan.rateKind).toBe('market');
    expect(buildGoalPlanView(goal, ctx({ reanchorPending: true })).firstYear!.rows[0].plan.rateKind).toBe('cash');
  });

  it('plans a 529 added to a cash first-year-only goal at the market rate', () => {
    const goal = fundGoal([fundKid('A', 2028, 11, [2028, 11], 20800)], { plan529Target: 47_800, brokerageTarget: 0 }, {
      planRateKind: 'cash', planRate: CASH_RATE,
    });
    const view = buildGoalPlanView(goal, ctx({ reanchorPending: true }));
    const row = view.kids!.find(k => k.account === 'plan529')!.perKid[0];
    expect(row.plan.n).toBe(240);
    expect(row.plan.rateKind).toBe('market');
    expect(row.plan.monthly).toBe(63);
    expect(row.plan.newYearLump).toBe(0);
    expect(row.plan.lumps).toEqual([]);
  });

  it('posts gift days to the brokerage when it has a target, else to the 529', () => {
    const kid = (): KidInput => ({ label: 'A', birthYear: null, birthMonth: null, accounts: { plan529: account(), brokerage: account() } });
    const both = buildGoalPlanView(base({ type: 'KIDS', config: { plan529Target: 47_800, brokerageTarget: 100_000, giftDays: true, kids: [kid()] } }), ctx());
    expect(both.kids!.find(k => k.account === 'brokerage')!.perKid[0].plan.newYearLump).toBe(1000);
    expect(both.kids!.find(k => k.account === 'plan529')!.perKid[0].plan.newYearLump).toBe(0);
    // $1,000 every January for 212 months grows past $47,800 on its own, so this one uses $123,960.
    const only529 = buildGoalPlanView(base({ type: 'KIDS', config: { plan529Target: 123_960, brokerageTarget: 0, giftDays: true, kids: [kid()] } }), ctx());
    expect(only529.kids![0].perKid[0].plan.newYearLump).toBe(1000);
  });

  it('grades a cash fund beside a market 529 at the cash rate', () => {
    const anchoredAt = '2026-11-15T00:00:00';
    const loggedAt = '2028-10-10T00:00:00';
    const anchor529 = proposeKidAccountPlan({
      target: 47_800, balance: 0, birthYear: 2028, birthMonth: 11,
      marketRate: 0.10, rateKindOverride: 'market', now: new Date(anchoredAt), giftDays: false,
    });
    const bal529 = futureValue(0, anchor529.monthly, 23, 0.10, anchor529.lumps.filter(l => l.monthIndex <= 23));
    const balFund = futureValue(0, 834, 23, 0.04, []);
    expect(balFund).toBeCloseTo(19_902, -1);
    const goal = fundGoal([
      fundKid('A', 2028, 11, [2028, 11], 20800,
        account(balFund, { balanceUpdatedAt: loggedAt, anchorBalance: 0, anchorMonthly: 834 }),
        account(bal529, { balanceUpdatedAt: loggedAt, anchorBalance: 0, anchorMonthly: anchor529.monthly })),
    ], { plan529Target: 47_800, brokerageTarget: 0 }, { planAnchoredAt: anchoredAt });
    const view = buildGoalPlanView(goal, ctx({ now: new Date(2028, 9, 15) }));
    expect(view.status.kind).toBe('on_plan');
    expect(view.status.delta).toBeCloseTo(0, 0);
  });

  it('is not behind six months after a fully funded fund ended, beside a 529 that is on plan', () => {
    const anchoredAt = '2026-11-15T00:00:00';
    const loggedAt = '2028-04-10T00:00:00'; // 17 months after the anchor
    const anchor529 = proposeKidAccountPlan({
      target: 47_800, balance: 0, birthYear: 2028, birthMonth: 11,
      marketRate: 0.10, rateKindOverride: 'market', now: new Date(anchoredAt), giftDays: false,
    });
    const bal529 = futureValue(0, anchor529.monthly, 17, 0.10, anchor529.lumps.filter(l => l.monthIndex <= 17));
    const goal = fundGoal([
      fundKid('A', 2028, 11, [2027, 11], 20800,
        account(20_800, { balanceUpdatedAt: '2027-10-10T00:00:00', anchorBalance: 0, anchorMonthly: 1702 }),
        account(bal529, { balanceUpdatedAt: loggedAt, anchorBalance: 0, anchorMonthly: anchor529.monthly })),
    ], { plan529Target: 47_800, brokerageTarget: 0 }, { planAnchoredAt: anchoredAt });
    const view = buildGoalPlanView(goal, ctx({ now: new Date(2028, 4, 15) }));
    expect(view.firstYear!.rows[0].plan.finished).toBeTrue();
    expect(view.status.kind).toBe('on_plan');
    expect(view.status.delta).toBeCloseTo(0, 0);
  });

  it('drops a finished fund from the hero total, status and progress and hides it after 12 months', () => {
    const targets = { plan529Target: 100_000, brokerageTarget: 0 };
    const kids = [
      fundKid('A', 2026, 11, [2026, 11], 20800, account(20_000, { balanceUpdatedAt: '2026-10-01T00:00:00', anchorMonthly: 800 }), account(10_000)),
      fundKid('B', 2025, 10, [2025, 10], 20800),
      fundKid('C', 2025, 11, [2025, 11], 20800),
    ];
    const withFund = buildGoalPlanView(fundGoal(kids, targets), ctx());
    const noFund = buildGoalPlanView(
      fundGoal(kids.map(k => ({ ...k, accounts: { ...k.accounts, firstYear: null } })), targets), ctx());
    expect(withFund.monthlyTotal).toBe(noFund.monthlyTotal);
    expect(withFund.firstYear!.totalMonthly).toBe(0);
    expect(withFund.firstYear!.endsLabel).toBeNull();
    expect(withFund.progressPct).toBeCloseTo((10_000 / 300_000) * 100, 6);
    expect(withFund.status).toEqual(noFund.status);
    expect(withFund.firstYear!.rows.map(r => r.plan.finished)).toEqual([true, true, true]);
    expect(withFund.firstYear!.rows.map(r => r.hidden)).toEqual([false, true, false]);
    expect(withFund.firstYear!.rows[0].plan.monthly).toBe(0);
  });

  it('says Ready for the first year and never 18th birthday for a fund-only goal', () => {
    const goal = fundGoal([fundKid('A', null, null, [2029, 11], 20800)], noTargets);
    const view = buildGoalPlanView(goal, ctx());
    expect(view.headline).toBe('Ready for the first year');
    expect(view.subtitle).toBe('$498/mo for A');
    expect(view.kids).toEqual([]);
    expect(view.rateKind).toBe('market');
    expect(view.status.kind).toBe('started');

    const two = fundGoal([fundKid('A', null, null, [2029, 11], 20800), fundKid('B', null, null, [2029, 11], 20800)], noTargets);
    expect(buildGoalPlanView(two, ctx()).subtitle).toBe('$996/mo for both kids');
  });

  it('keeps the usual headline when a 529 target exists beside the fund', () => {
    const goal = fundGoal([fundKid('A', 2028, 11, [2028, 11], 20800)], { plan529Target: 47_800, brokerageTarget: 0 });
    expect(buildGoalPlanView(goal, ctx()).headline).toBe('Ready by their 18th birthday');
  });

  it('reads started with zero progress when only finished funds remain and there is no 529 or brokerage target', () => {
    const goal = fundGoal([fundKid('A', 2026, 11, [2026, 11], 20800, account(20_800, { balanceUpdatedAt: '2026-10-01T00:00:00' }))], noTargets);
    const view = buildGoalPlanView(goal, ctx());
    expect(view.status.kind).toBe('started');
    expect(view.progressPct).toBe(0);
    expect(view.monthlyTotal).toBe(0);
    expect(view.rateKind).toBe('market');
  });

  it('names the fund share end month', () => {
    const goal = fundGoal([
      fundKid('A', 2028, 11, [2028, 11], 20800),
      fundKid('B', null, null, [2029, 11], 20800),
      fundKid('C', 2026, 6, [2026, 6], 20800),
    ], noTargets);
    expect(buildGoalPlanView(goal, ctx()).firstYear!.endsLabel).toBe('Nov 2029');
  });

  it('omits the 529 card when its target is 0', () => {
    const goal = fundGoal([fundKid('A', null, null, [2029, 11], 20800)], { plan529Target: 0, brokerageTarget: 50_000 });
    expect(buildGoalPlanView(goal, ctx()).kids!.map(k => k.account)).toEqual(['brokerage']);
  });

  it('renders a stored kids goal whose new fields are explicit nulls', () => {
    const config: KidsConfig = {
      plan529Target: 100_000, brokerageTarget: 0, firstYearFund: null,
      kids: [{
        label: 'Ada', birthYear: Y - 2, birthMonth: 3,
        firstYearTarget: null, firstYearDueYear: null, firstYearDueMonth: null,
        accounts: { plan529: account(), brokerage: account(), firstYear: null },
      }],
    };
    const view = buildGoalPlanView(base({ type: 'KIDS', config }), ctx());
    expect(view.firstYear).toBeUndefined();
    expect(view.monthlyTotal).toBeGreaterThan(0);
  });
});

describe('buildGoalPlanView final review fixes', () => {
  it('is not Behind for a simple goal logged six months after its deadline at the planned amount', () => {
    // Rate 0, $1,100 a month for the 11 months to Dec 2026 plans $12,100 against a $12,000 target.
    const goal = base({
      config: { targetAmount: 12_000, targetYear: 2026 },
      currentBalance: 11_990, anchorBalance: 0, anchorMonthly: 1_100, planRate: 0,
      planAnchoredAt: '2026-01-15T00:00:00', balanceUpdatedAt: '2027-06-15T00:00:00',
    });
    const view = buildGoalPlanView(goal, ctx({ now: new Date(2027, 5, 20) }));
    expect(view.status.kind).toBe('on_plan');
  });

  it('stops a kids goal expected balance at the latest account deadline', () => {
    // One account, rate 0, $100 a month for the 12 months to the 18th birthday in Jan 2027.
    const goal = base({
      type: 'KIDS',
      config: {
        plan529Target: 1_250, brokerageTarget: 0,
        kids: [{
          label: 'Teen', birthYear: 2009, birthMonth: 1,
          accounts: {
            plan529: account(1_190, { anchorMonthly: 100, balanceUpdatedAt: '2027-07-15T00:00:00' }),
            brokerage: account(0),
          },
        }],
      } as KidsConfig,
      planRate: 0, planAnchoredAt: '2026-01-15T00:00:00',
    });
    const view = buildGoalPlanView(goal, ctx({ now: new Date(2027, 6, 20) }));
    expect(view.status.kind).toBe('on_plan');
  });

  it('shows what is due in the deadline month for a simple goal', () => {
    const goal = base({ config: { targetAmount: 30_000, targetYear: 2026 }, currentBalance: 24_000 });
    const view = buildGoalPlanView(goal, ctx({ now: new Date(2026, 11, 5) }));
    expect(view.monthlyTotal).toBe(0);
    expect(view.dueNow).toBe(6_000);
    expect(view.headline).toBe('Target month reached');
    expect(view.subtitle).toBe('$6,000 due now Boat fund');
    expect(view.heroLabel).toBe('Due now');
    expect(view.simple!.fromGrowth).toBe(0);
  });

  it('sums what is due on kid accounts in their deadline month', () => {
    const goal = base({
      type: 'KIDS',
      config: {
        plan529Target: 50_000, brokerageTarget: 0,
        kids: [{ label: 'Teen', birthYear: 2008, birthMonth: 12, accounts: { plan529: account(30_000), brokerage: account(0) } }],
      } as KidsConfig,
    });
    const view = buildGoalPlanView(goal, ctx({ now: new Date(2026, 11, 5) }));
    expect(view.monthlyTotal).toBe(0);
    expect(view.dueNow).toBe(20_000);
    expect(view.subtitle).toBe('$20,000 due now for Teen');
    expect(view.heroLabel).toBe('Due now');
  });

  it('keeps the monthly subtitle and no due amount while months remain', () => {
    const view = buildGoalPlanView(base({}), ctx());
    expect(view.dueNow).toBe(0);
    expect(view.subtitle).toMatch(/^\$[\d,]+\/mo Boat fund$/);
    expect(view.heroLabel).toBe('Save this month');
  });

  it('reads Plan started for a vacation created in November with nothing saved', () => {
    const goal = base({
      type: 'VACATION',
      config: { tripsPerYear: 2, budgetPerTrip: 3_000, savedThisYear: 0, savedYear: Y },
      planAnchoredAt: '2026-11-10T00:00:00',
    });
    const view = buildGoalPlanView(goal, ctx());
    expect(view.status.kind).toBe('started');
    expect(view.vacation!.expectedSoFar).toBe(0);
  });

  it('leaves a zero-target kid account out of the status and progress', () => {
    const goal = base({
      type: 'KIDS',
      config: {
        plan529Target: 100_000, brokerageTarget: 0,
        kids: [{
          label: 'Ada', birthYear: Y - 2, birthMonth: 3,
          accounts: {
            plan529: account(1_000, { anchorBalance: 1_000, balanceUpdatedAt: '2026-06-01T00:00:00' }),
            brokerage: account(5_000, { anchorBalance: 5_000, anchorMonthly: 50, balanceUpdatedAt: '2026-09-01T00:00:00' }),
          },
        }],
      } as KidsConfig,
    });
    const view = buildGoalPlanView(goal, ctx());
    expect(view.progressPct).toBeCloseTo(1, 6);
    expect(view.status.asOf).toBe('2026-06-01T00:00:00');
  });
});

// ── One-screen plan page: hero copy, timeline, pills, gift days, How FRED helps ──

describe('buildGoalPlanView one-screen page', () => {
  const ANCHOR = '2026-11-15T00:00:00';
  const INVEST_LEAD = (m: number) =>
    `Already investing ${fmtMoney(m)} or more a month? FRED sets this plan's share aside inside your portfolio`;

  function kid(label: string, birthYear: number | null, birthMonth: number | null, fund?: { due: [number, number]; target: number }): KidInput {
    return {
      label, birthYear, birthMonth,
      ...(fund
        ? { firstYearTarget: fund.target, firstYearDueYear: fund.due[0], firstYearDueMonth: fund.due[1] }
        : {}),
      accounts: { plan529: account(), brokerage: account(), ...(fund ? { firstYear: account() } : {}) },
    };
  }

  function family(kids: KidInput[], extra: Partial<KidsConfig> = {}, goalExtra: Partial<GoalPlanInput> = {}): GoalPlanInput {
    const config: KidsConfig = { plan529Target: 47_800, brokerageTarget: 100_000, kids, ...extra };
    return base({ type: 'KIDS', config, planAnchoredAt: ANCHOR, ...goalExtra });
  }

  /** Ellie born 2024, Theo due Jun 2028 with a baby fund, and an undated kid with a baby fund. */
  const sample = (extra: Partial<KidsConfig> = {}) => family([
    kid('Ellie', 2024, null),
    kid('Theo', 2028, 6, { due: [2028, 6], target: 20_800 }),
    kid('', null, null, { due: [2029, 11], target: 16_500 }),
  ], extra);

  it('KIDS subtitle reads "$X/mo for all 3 kids" and audience uses a single kid label', () => {
    const view = buildGoalPlanView(sample(), ctx());
    expect(view.audience).toBe('for all 3 kids');
    expect(view.subtitle).toBe(`${fmtMoney(view.monthlyTotal)}/mo for all 3 kids`);
    expect(view.heroLabel).toBe('Save this month');

    const one = buildGoalPlanView(family([kid('Ellie', 2024, null)]), ctx());
    expect(one.audience).toBe('for Ellie');
    expect(one.subtitle).toBe(`${fmtMoney(one.monthlyTotal)}/mo for Ellie`);
    expect(buildGoalPlanView(family([kid(' ', 2024, null)]), ctx()).audience).toBe('for your kid');
    expect(buildGoalPlanView(family([kid('A', 2024, null), kid('B', 2025, 3)]), ctx()).audience).toBe('for both kids');
  });

  it('names the audience of every other goal type', () => {
    const audience = (partial: Partial<GoalPlanInput>) => buildGoalPlanView(base(partial), ctx()).audience;
    expect(audience({ type: 'HOUSE', config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6 } })).toBe('for your home');
    expect(audience({ type: 'CAR', config: { price: 40_000, downPaymentPct: null, targetYear: Y + 4 } })).toBe('for your car');
    expect(audience({ type: 'VACATION', config: { tripsPerYear: 3, budgetPerTrip: 2_000, savedThisYear: 0, savedYear: Y } })).toBe('for 3 trips a year');
    expect(audience({ type: 'VACATION', config: { tripsPerYear: 1, budgetPerTrip: 2_000, savedThisYear: 0, savedYear: Y } })).toBe('for your trip');
    expect(audience({ type: 'EMERGENCY_FUND', config: { monthlyExpenses: 4_000, months: 6, targetYear: Y + 2 } })).toBe('for your safety net');
    expect(audience({ type: 'WEDDING', config: { targetAmount: 30_000, targetYear: Y + 2 } })).toBe('for the wedding');
    expect(audience({})).toBe('Boat fund');
    expect(audience({ name: '  ' })).toBe('My goal');
  });

  it('giftDays payoff: monthlyIfOn is lower by about $1,000 × kids ÷ 12 and perPaycheckIfOn follows', () => {
    const off = buildGoalPlanView(sample(), ctx());
    expect(off.giftDays).toBeDefined();
    const g = off.giftDays!;
    expect(g.on).toBeFalse();
    expect(g.available).toBeTrue();
    expect(g.lump).toBe(GIFT_DAY_LUMP);
    const drop = off.monthlyTotal - g.monthlyIfOn;
    const even = (GIFT_DAY_LUMP * 3) / 12;
    expect(drop).toBeGreaterThan(even * 0.9);
    expect(drop).toBeLessThan(even * 1.25);
    expect(g.perPaycheckIfOn).toBe(InvestmentFrequencyUtils.toPerPaycheckEquivalent(g.monthlyIfOn, 'biweekly'));
    expect(off.howFredHelps).toContain(fmtMoney(off.monthlyTotal));

    // Stored on: the hero shows the lower monthly, and the payoff equals it.
    const on = buildGoalPlanView(sample({ giftDays: true }), ctx());
    expect(on.monthlyTotal).toBe(g.monthlyIfOn);
    expect(on.giftDays!.on).toBeTrue();
    expect(on.giftDays!.monthlyIfOn).toBe(on.monthlyTotal);
    expect(on.perPaycheck).toBe(g.perPaycheckIfOn);
    expect(on.howFredHelps).toContain(fmtMoney(g.monthlyIfOn));

    // No schedule: no per paycheck figure.
    expect(buildGoalPlanView(sample(), ctx({ frequency: '' })).giftDays!.perPaycheckIfOn).toBeNull();
  });

  it('giftDays is absent when no kid unit has giftDaysAvailable', () => {
    // $1,000 every January from now to 18 grows past a $20,000 nest egg on its own.
    const small = family([kid('A', null, null), kid('B', Y - 1, 4)], { plan529Target: 0, brokerageTarget: 20_000 });
    expect(buildGoalPlanView(small, ctx()).giftDays).toBeUndefined();
    // A kid within 24 months of 18 is never offered.
    const teen = family([kid('Teen', Y - 17, 3)]);
    expect(buildGoalPlanView(teen, ctx()).giftDays).toBeUndefined();
  });

  it('gift days go to the nest egg only: a nest egg the lumps would outgrow hides the offer even beside a large 529', () => {
    const goal = family([kid('A', null, null)], { plan529Target: 123_960, brokerageTarget: 20_000, giftDays: true });
    const view = buildGoalPlanView(goal, ctx());
    expect(view.giftDays).toBeUndefined();
    const lumps = view.kids!.map(a => a.perKid[0].plan.newYearLump);
    expect(lumps).toEqual([0, 0]);
    // The 529 alone would have taken it.
    expect(view.kids!.find(a => a.account === 'plan529')!.perKid[0].plan.giftDaysAvailable).toBeTrue();
  });

  it('the offer needs EVERY receiving unit to take the lump: one nest egg the lumps outgrow hides it for the family (R7)', () => {
    // Kid A has 18 Januaries ahead: $1,000 each grows past a $20,000 nest egg on its own.
    // Kid B is four years from 18: three or four lumps, well under the target, so B alone could take it.
    const goal = family([kid('A', null, null), kid('B', Y - 14, 3)], { plan529Target: 47_800, brokerageTarget: 20_000 });
    const view = buildGoalPlanView(goal, ctx());
    const nest = view.kids!.find(a => a.account === 'brokerage')!;
    expect(nest.perKid.map(k => k.plan.giftDaysAvailable)).toEqual([false, true]);
    expect(view.giftDays).toBeUndefined();

    // With gift days stored on, no unit posts a lump either: the family either all takes it or none does.
    const on = buildGoalPlanView(family([kid('A', null, null), kid('B', Y - 14, 3)], { plan529Target: 47_800, brokerageTarget: 20_000, giftDays: true }), ctx());
    expect(on.kids!.find(a => a.account === 'brokerage')!.perKid.map(k => k.plan.newYearLump)).toEqual([0, 0]);
    expect(on.giftDays).toBeUndefined();
  });

  it('pins the sample family hero figures: $47,800 college and $100,000 nest egg per kid, baby funds for the two unborn, 10%, biweekly', () => {
    const off = buildGoalPlanView(sample(), ctx());
    expect(off.heroLabel).toBe('Save this month');
    expect(off.audience).toBe('for all 3 kids');
    // The spec copy says $2,830/mo and ~$1,306 a paycheck, dropping to ~$2,580 and ~$1,191 with gift
    // days on; those figures were drawn with a $250,000 nest egg. At the spec's $100,000 default the
    // real numbers are pinned here (reported to the controller in the Task 6 report).
    expect(Math.round(off.monthlyTotal)).toBe(2_249);
    expect(Math.round(off.perPaycheck!)).toBe(1_036);
    expect(off.giftDays).toBeDefined();
    expect(Math.round(off.giftDays!.monthlyIfOn)).toBe(1_988);
    expect(Math.round(off.giftDays!.perPaycheckIfOn!)).toBe(916);
    expect(off.subtitle).toBe('$2,249/mo for all 3 kids');
  });

  it('pills: college and nest egg are Invested at 18 years out, a baby fund due in 19 months is Cash', () => {
    const view = buildGoalPlanView(family([kid('Theo', 2028, 6, { due: [2028, 6], target: 20_800 })]), ctx());
    for (const acct of view.kids!) expect(acct.perKid[0].pill).withContext(acct.account).toBe('Invested');
    expect(view.firstYear!.rows[0].plan.n).toBe(19);
    expect(view.firstYear!.rows[0].pill).toBe('Cash');
    expect(view.pill).toBeUndefined();

    // A kid within 36 months of 18 is Cash; an undated kid's fund (36-month runway) is Invested.
    const mixed = buildGoalPlanView(family([
      kid('Teen', Y - 16, 6),
      kid('', null, null, { due: [2029, 11], target: 20_800 }),
    ]), ctx());
    for (const acct of mixed.kids!) {
      expect(acct.perKid[0].pill).toBe('Cash');
      expect(acct.perKid[1].pill).toBe('Invested');
    }
    expect(mixed.firstYear!.rows[0].pill).toBe('Invested');

    // Simple goals: one pill on the view.
    expect(buildGoalPlanView(base({ type: 'HOUSE', config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6 } }), ctx()).pill).toBe('Invested');
    expect(buildGoalPlanView(base({ type: 'CAR', config: { price: 40_000, downPaymentPct: null, targetYear: Y + 1 } }), ctx()).pill).toBe('Cash');
    expect(buildGoalPlanView(base({ type: 'EMERGENCY_FUND', config: { monthlyExpenses: 4_000, months: 6, targetYear: Y + 5 } }), ctx()).pill).toBe('Cash');
    expect(buildGoalPlanView(base({ type: 'VACATION', config: { tripsPerYear: 2, budgetPerTrip: 2_000, savedThisYear: 0, savedYear: Y } }), ctx()).pill).toBe('Cash');
  });

  it('howFredHelps: all invested, mixed (names the baby funds), all cash (vacation)', () => {
    const invested = buildGoalPlanView(family([kid('A', 2020, 3), kid('B', 2023, 8)]), ctx());
    expect(invested.howFredHelps).toBe(`${INVEST_LEAD(invested.monthlyTotal)} and handles where it goes.`);

    const oneFund = buildGoalPlanView(family([kid('Theo', 2028, 6, { due: [2028, 6], target: 20_800 })]), ctx());
    expect(oneFund.howFredHelps).toBe(`${INVEST_LEAD(oneFund.monthlyTotal)}, keeps the baby fund in cash, and handles where it goes.`);

    const twoFunds = buildGoalPlanView(family([
      kid('Theo', 2028, 6, { due: [2028, 6], target: 20_800 }),
      kid('Mia', 2028, 9, { due: [2028, 9], target: 16_500 }),
    ]), ctx());
    expect(twoFunds.howFredHelps).toBe(`${INVEST_LEAD(twoFunds.monthlyTotal)}, keeps the baby funds in cash, and handles where it goes.`);

    // A cash unit that is not a baby fund: a kid within 36 months of 18.
    const teen = buildGoalPlanView(family([kid('Teen', Y - 16, 6), kid('B', 2023, 8)]), ctx());
    expect(teen.howFredHelps).toBe(`${INVEST_LEAD(teen.monthlyTotal)}, keeps the short-dated money in cash, and handles where it goes.`);

    const cash = 'FRED keeps this in cash, ready the day you need it.';
    expect(buildGoalPlanView(base({ type: 'VACATION', config: { tripsPerYear: 2, budgetPerTrip: 2_000, savedThisYear: 0, savedYear: Y } }), ctx()).howFredHelps).toBe(cash);
    expect(buildGoalPlanView(base({ type: 'EMERGENCY_FUND', config: { monthlyExpenses: 4_000, months: 6, targetYear: Y + 2 } }), ctx()).howFredHelps).toBe(cash);
    expect(buildGoalPlanView(family([kid('Teen', Y - 16, 6)]), ctx()).howFredHelps).toBe(cash);
    const house = buildGoalPlanView(base({ type: 'HOUSE', config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6 } }), ctx());
    expect(house.howFredHelps).toBe(`${INVEST_LEAD(house.monthlyTotal)} and handles where it goes.`);
  });

  it('a plan with no unborn kid has no fund rows and no timeline undated dots', () => {
    const view = buildGoalPlanView(family([kid('Ava', 2019, 4), kid('Ben', 2022, 9)]), ctx());
    expect(view.firstYear).toBeUndefined();
    expect(view.timeline).toBeDefined();
    expect(view.timeline!.dots.some(d => d.undated)).toBeFalse();
    expect(buildGoalPlanView(base({}), ctx()).timeline).toBeUndefined();
  });

  it('builds the family timeline from the kids and today', () => {
    const goal = sample();
    const view = buildGoalPlanView(goal, ctx());
    expect(view.timeline).toEqual(buildKidTimeline((goal.config as KidsConfig).kids, NOW));
    // The undated kid is parked just before the end.
    expect(view.timeline!.dots.map(d => d.label)).toEqual(['Ellie', 'Now', 'Theo', 'Kid 3', 'All 18']);
  });

  it('treats a kid expected this year with no month as not yet born: a dated dot, a December baby fund', () => {
    const k = kid('Ellie', Y, null);
    const goal = family([{ ...k, firstYearTarget: 25_700, accounts: { ...k.accounts, firstYear: account() } }]);
    const view = buildGoalPlanView(goal, ctx());
    const dot = view.timeline!.dots.find(d => d.kind === 'kid')!;
    expect(dot.undated).toBeFalse();
    expect(dot.passed).toBeFalse();
    expect(dot.year).toBe(Y);
    expect(view.firstYear!.rows[0].plan.due).toEqual({ year: Y, month: 12 });
    expect(view.firstYear!.rows[0].plan.finished).toBeFalse();
  });

  it('ends a this-year baby fund in December for a goal anchored last year', () => {
    const k = kid('Ellie', Y, null);
    const goal = family(
      [{ ...k, firstYearTarget: 25_700, accounts: { ...k.accounts, firstYear: account() } }],
      {},
      { planAnchoredAt: `${Y - 1}-06-15T00:00:00` },
    );
    const row = buildGoalPlanView(goal, ctx()).firstYear!.rows[0];
    expect(row.plan.due).toEqual({ year: Y, month: 12 });
    expect(row.plan.n).toBe(1);
    expect(row.plan.finished).toBeFalse();
  });

  // ── What the monthly covers, and Why invest it ──

  const MONEY = String.raw`\$[\d,]+`;
  const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const KIDS_WHY = new RegExp('^' + escape(
    'You will not spend the funds for years: college and the nest egg come due at 18, and those years are '
    + 'what make invested money grow. Of the ',
  ) + `(${MONEY})` + escape(' this plan reaches, ~') + `(${MONEY})` + escape(' comes from you and growth adds ~')
    + `(${MONEY})` + escape(
    '. Take it out early and growth stops, and the monthly from you goes up. Anything you need within three '
    + 'years is planned as cash instead.',
  ) + '$');
  const SIMPLE_WHY = new RegExp('^' + escape(
    'You will not spend this for years, and those years are what make invested money grow. Of the ',
  ) + `(${MONEY})` + escape(' this plan reaches, ~') + `(${MONEY})` + escape(' comes from you and growth adds ~')
    + `(${MONEY})` + escape('. Take it out early and growth stops, and the monthly from you goes up.') + '$');
  const dollars = (s: string) => Number(s.replace(/[$,]/g, ''));
  it('coversLine names the funds, says which sit on top of everyday costs, and prices the kids at home by age', () => {
    // Ellie is 2, Theo arrives in 2028, the third kid is undated: one kid at home (only-child factor), two funds.
    expect(buildGoalPlanView(sample(), ctx()).coversLine).toBe(
      'Only the funds: the baby funds, college and the nest egg. College and the nest egg are on top of everyday '
      + 'costs; the baby funds are the first year of them, saved ahead. Everyday costs for Ellie (2) run ~$1,910 a month '
      + "by FRED's estimate; a kid who has not arrived yet costs nothing until they do.",
    );

    // Two at home, ages 6 (6-8 band) and 3 (3-5 band), two-kid factor 1.00: (12,350 + 12,730) x inflation / 12.
    const two = buildGoalPlanView(family([kid('A', 2020, 3), kid('B', 2023, 8)]), ctx());
    expect(two.coversLine).toBe(
      'Only the funds: college and the nest egg. College and the nest egg are on top of everyday costs. '
      + "Everyday costs for A (6) and B (3) run ~$2,970 a month by FRED's estimate.",
    );

    const collegeOnly = buildGoalPlanView(family([kid('A', 2020, 3)], { brokerageTarget: 0 }), ctx());
    expect(collegeOnly.coversLine).toBe(
      'Only the funds: college. College is on top of everyday costs. '
      + "Everyday costs for A (6) run ~$1,860 a month by FRED's estimate.",
    );

    const newborn = buildGoalPlanView(family([kid('Mae', 2026, 10)], { plan529Target: 0 }), ctx());
    expect(newborn.coversLine).toBe(
      'Only the funds: the nest egg. The nest egg is on top of everyday costs. '
      + "Everyday costs for Mae (under 1) run ~$1,910 a month by FRED's estimate.",
    );

    const fundOnly = buildGoalPlanView(
      family([kid('Theo', 2028, 6, { due: [2028, 6], target: 20_800 })], { plan529Target: 0, brokerageTarget: 0 }),
      ctx(),
    );
    expect(fundOnly.coversLine).toBe(
      'Only the funds: the baby fund. The baby fund is the first year of everyday costs, saved ahead. '
      + 'No everyday costs yet; they start when the first kid arrives.',
    );
  });

  it('whyInvestLine names only the invested units the family has', () => {
    const collegeOnly = buildGoalPlanView(family([kid('A', 2020, 3)], { brokerageTarget: 0 }), ctx());
    expect(collegeOnly.whyInvestLine).toMatch(/^You will not spend the funds for years: college comes due at 18, and/);
    const nestOnly = buildGoalPlanView(family([kid('A', 2020, 3)], { plan529Target: 0 }), ctx());
    expect(nestOnly.whyInvestLine).toMatch(/^You will not spend the funds for years: the nest egg comes due at 18, and/);
  });

  it('whyInvestLine for the sample family: from you plus growth plus balances is what the invested units reach', () => {
    const view = buildGoalPlanView(sample(), ctx());
    expect(view.whyInvestLine).not.toBeNull();
    const m = view.whyInvestLine!.match(KIDS_WHY);
    expect(m).withContext(view.whyInvestLine!).not.toBeNull();
    const [reaches, fromYou, fromGrowth] = [m![1], m![2], m![3]].map(dollars);
    expect(fromYou % 1_000).toBe(0);
    expect(fromGrowth % 1_000).toBe(0);
    expect(fromGrowth).toBeGreaterThan(0);

    // The invested units: every 529 and brokerage plan here, plus the undated kid's market baby fund.
    const targetOf = { plan529: 47_800, brokerage: 100_000 };
    const invested: Array<{ target: number; balance: number }> = [
      ...view.kids!.reduce<Array<{ target: number; balance: number }>>((all, a) => all.concat(a.perKid
        .filter(k => k.plan.rateKind === 'market')
        .map(k => ({ target: targetOf[a.account], balance: k.balance }))), []),
      ...view.firstYear!.rows
        .filter(r => r.plan.rateKind === 'market' && !r.plan.finished)
        .map(r => ({ target: r.plan.target, balance: r.balance })),
    ];
    // Three kids' college and nest egg, plus the undated kid's $16,500 fund (Theo's is cash).
    expect(invested.length).toBe(7);
    expect(reaches).toBe(invested.reduce((sum, u) => sum + u.target, 0));
    expect(reaches).toBe(3 * (47_800 + 100_000) + 16_500);
    // The two shares add up to what the plan reaches (what is already saved counts as "from you").
    expect(Math.abs(fromYou + fromGrowth - reaches)).toBeLessThanOrEqual(1_000);
  });

  it('whyInvestLine counts a saved balance as from you, so the shares still add up', () => {
    const one = kid('A', 2020, 3);
    one.accounts.brokerage = account(25_000);
    const saved = buildGoalPlanView(family([one]), ctx());
    expect(saved.whyInvestLine).not.toBeNull();
    const m = saved.whyInvestLine!.match(KIDS_WHY);
    expect(m).withContext(saved.whyInvestLine!).not.toBeNull();
    const [reaches, fromYou, fromGrowth] = [m![1], m![2], m![3]].map(dollars);
    expect(reaches).toBe(47_800 + 100_000);
    expect(fromYou).toBeGreaterThanOrEqual(25_000);
    expect(Math.abs(fromYou + fromGrowth - reaches)).toBeLessThanOrEqual(1_000);
  });

  it('a kids plan whose only unit is a cash baby fund has no whyInvestLine but still a coversLine', () => {
    const view = buildGoalPlanView(
      family([kid('Theo', 2028, 6, { due: [2028, 6], target: 20_800 })], { plan529Target: 0, brokerageTarget: 0 }),
      ctx(),
    );
    expect(view.firstYear!.rows[0].pill).toBe('Cash');
    expect(view.whyInvestLine).toBeNull();
    expect(view.coversLine).not.toBeNull();
  });

  it('a due-now plan has neither line', () => {
    const kids = base({
      type: 'KIDS',
      config: {
        plan529Target: 50_000, brokerageTarget: 0,
        kids: [{ label: 'Teen', birthYear: 2008, birthMonth: 12, accounts: { plan529: account(30_000), brokerage: account(0) } }],
      } as KidsConfig,
    });
    const k = buildGoalPlanView(kids, ctx({ now: new Date(2026, 11, 5) }));
    expect(k.heroLabel).toBe('Due now');
    expect(k.coversLine).toBeNull();
    expect(k.whyInvestLine).toBeNull();

    const simple = buildGoalPlanView(
      base({ config: { targetAmount: 30_000, targetYear: 2026 }, currentBalance: 24_000 }),
      ctx({ now: new Date(2026, 11, 5) }),
    );
    expect(simple.heroLabel).toBe('Due now');
    expect(simple.coversLine).toBeNull();
    expect(simple.whyInvestLine).toBeNull();
  });

  it('a HOUSE plan at the market rate reads the simple-plan first sentence and has no coversLine', () => {
    const view = buildGoalPlanView(
      base({ type: 'HOUSE', currentBalance: 12_000, config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6 } }),
      ctx(),
    );
    expect(view.coversLine).toBeNull();
    const m = view.whyInvestLine!.match(SIMPLE_WHY);
    expect(m).withContext(view.whyInvestLine!).not.toBeNull();
    const [reaches, fromYou, fromGrowth] = [m![1], m![2], m![3]].map(dollars);
    expect(reaches).toBe(100_000);
    // The $12,000 already saved is "from you" too.
    expect(fromYou).toBe(Math.round((view.simple!.fromYou + 12_000) / 1_000) * 1_000);
    expect(fromGrowth).toBe(Math.round(view.simple!.fromGrowth / 1_000) * 1_000);
  });

  it('cash plans have neither line: emergency fund, vacation, a short-dated car', () => {
    const lines = (partial: Partial<GoalPlanInput>) => {
      const v = buildGoalPlanView(base(partial), ctx());
      return [v.coversLine, v.whyInvestLine];
    };
    expect(lines({ type: 'EMERGENCY_FUND', config: { monthlyExpenses: 4_000, months: 6, targetYear: Y + 5 } })).toEqual([null, null]);
    expect(lines({ type: 'VACATION', config: { tripsPerYear: 2, budgetPerTrip: 2_000, savedThisYear: 0, savedYear: Y } })).toEqual([null, null]);
    expect(lines({ type: 'CAR', config: { price: 40_000, downPaymentPct: null, targetYear: Y + 1 } })).toEqual([null, null]);
  });
});
