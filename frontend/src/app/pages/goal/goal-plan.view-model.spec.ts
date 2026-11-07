import { buildGoalPlanView, fmtCompact, GoalPlanContext, GoalPlanInput } from './goal-plan.view-model';
import { AccountState, KidInput, KidsConfig } from '../../models/goal.model';
import { futureValue, proposeKidAccountPlan } from '../../utils/goal-plan.utils';
import { CASH_RATE } from '../../services/rate-assumptions.service';

const NOW = new Date(2026, 10, 15); // 15 Nov 2026
const Y = NOW.getFullYear();

function account(balance = 0, extra: Partial<AccountState> = {}): AccountState {
  return { balance, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0, ...extra };
}

function base(partial: Partial<GoalPlanInput>): GoalPlanInput {
  return {
    type: 'CUSTOM',
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
    expect(kidsView.subtitle).toMatch(new RegExp(`^\\$[\\d,]+/mo · 3 kids · ${Y + 14} to ${Y + 18}$`));

    const undecided = kidsGoal([
      { label: 'A', birthYear: null, birthMonth: null, accounts: { plan529: account(), brokerage: account() } },
      { label: 'B', birthYear: Y + 2, birthMonth: 4, accounts: { plan529: account(), brokerage: account() } },
    ]);
    const relView = buildGoalPlanView(undecided, ctx());
    expect(relView.subtitle).toMatch(/^\$[\d,]+\/mo · 2 kids · 18-year plans$/);
    expect(relView.relative).toBeTrue();
    expect(relView.kids![0].perKid.every(k => k.relative)).toBeTrue();

    const vacation = buildGoalPlanView(base({
      type: 'VACATION',
      config: { tripsPerYear: 4, budgetPerTrip: 2_500, savedThisYear: 0, savedYear: Y },
    }), ctx());
    expect(vacation.subtitle).toBe('$834/mo · 4 trips a year');
    expect(vacation.vacation?.monthly).toBe(834);

    const house = buildGoalPlanView(base({
      type: 'HOUSE',
      config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6 },
    }), ctx());
    expect(house.subtitle).toMatch(new RegExp(`^\\$[\\d,]+/mo · 20% down · ${Y + 6}$`));

    const car = buildGoalPlanView(base({
      type: 'CAR',
      config: { price: 40_000, downPaymentPct: null, targetYear: Y + 4 },
    }), ctx());
    expect(car.subtitle).toMatch(new RegExp(`^\\$[\\d,]+/mo · full price · ${Y + 4}$`));

    const custom = buildGoalPlanView(base({}), ctx());
    expect(custom.subtitle).toMatch(new RegExp(`^\\$[\\d,]+/mo · \\$200K by ${Y + 6}$`));
    expect(custom.headline).toBe(`Reaches $200K in Dec ${Y + 6}`);

    const shortKid = kidsGoal([
      { label: 'Teen', birthYear: Y - 17, birthMonth: 12, accounts: { plan529: account(), brokerage: account() } },
    ]);

    const all = [kidsView, relView, vacation, house, car, custom, buildGoalPlanView(shortKid, ctx())];
    for (const v of all) {
      const text = [v.headline, v.subtitle].join(' ');
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

  it('always carries kid cost figures for a kids goal, sized by the kid count', () => {
    const three = kidsGoal([0, 1, 2].map(i => ({
      label: `K${i}`, birthYear: Y - 3 - i, birthMonth: 4, accounts: { plan529: account(), brokerage: account() },
    })));
    const view = buildGoalPlanView(three, ctx());
    expect(view.kidCosts?.kidsCount).toBe(3);
    expect(view.kidCosts?.perChildTotal).toBe(252393);
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
    expect(view.subtitle).toBe('$498/mo · 1 kid · first-year fund');
    expect(view.kids).toEqual([]);
    expect(view.rateKind).toBe('market');
    expect(view.status.kind).toBe('started');

    const two = fundGoal([fundKid('A', null, null, [2029, 11], 20800), fundKid('B', null, null, [2029, 11], 20800)], noTargets);
    expect(buildGoalPlanView(two, ctx()).subtitle).toBe('$996/mo · 2 kids · first-year fund');
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
    expect(view.kidCosts?.kidsCount).toBe(1);
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
    expect(view.subtitle).toBe('$6,000 due now · $30K by 2026');
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
    expect(view.subtitle.startsWith('$20,000 due now · 1 kid')).toBeTrue();
  });

  it('keeps the monthly subtitle and no due amount while months remain', () => {
    const view = buildGoalPlanView(base({}), ctx());
    expect(view.dueNow).toBe(0);
    expect(view.subtitle).toMatch(/^\$[\d,]+\/mo · /);
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
