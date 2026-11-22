import { buildGoalPlanView, fmtCompact, fmtMoney, GoalPlanContext, GoalPlanInput } from './goal-plan.view-model';
import { buildKidTimeline } from './kid-timeline.model';
import { AccountState, KidInput, KidsConfig, UnitTally } from '../../models/goal.model';
import { futureValue, proposeKidAccountPlan, proposeSimplePlan } from '../../utils/goal-plan.utils';
import { CASH_RATE } from '../../services/rate-assumptions.service';
import { InvestmentFrequencyUtils } from '../../utils/investment-frequency.utils';
import { everydayMonthlyForKid } from '../../utils/kid-cost.utils';

const NOW = new Date(2026, 10, 15); // 15 Nov 2026
const Y = NOW.getFullYear();

function tally(extra: Partial<UnitTally> = {}): UnitTally {
  return {
    tally: 0, creditedThrough: '2026-11-15', creditsUntil: null, nextCreditDate: '2026-12-01',
    nextCreditAmount: 450, net: 0, netSince: null, startedCrediting: true, ...extra,
  };
}

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
  it('aggregates kids accounts into one status and progress against your part; the accounts sum to investMonthly, not the hero', () => {
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
    expect(view.investMonthly).toBe(p529.totals.monthly + brokerage.totals.monthly);

    // 35K saved against: Ada's part (anchors plus 194 planned months, Jan 2026 to Mar 2042), and
    // Ben's targets, since his accounts plan no monthly.
    const ada = (8_000 + 200 * 194) + (15_000 + 300 * 194);
    const ben = 100_000 + 250_000;
    expect(view.progressPct).toBeCloseTo((35_000 / (ada + ben)) * 100, 6);
    // No unit has a tally yet: Plan started.
    expect(view.status.kind).toBe('started');
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
    expect(house.subtitle).toMatch(/^\$100,000 down · \$[\d,]+\/mo for your home$/);

    const car = buildGoalPlanView(base({
      type: 'CAR',
      config: { price: 40_000, downPaymentPct: null, targetYear: Y + 4 },
    }), ctx());
    // Full price saved (a legacy null pct) leaves no loan, so no loan line and no down-payment lead.
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

  it('reads on_plan from a credited kid unit with nothing missed; a stored giftDays flag adds no lump to the plan value', () => {
    const anchoredAt = '2025-11-01T00:00:00';
    const loggedAt = '2026-02-01T00:00:00'; // 3 months after the anchor, one January inside the window
    const anchorPlan = proposeKidAccountPlan({
      target: 100_000, balance: 0, birthYear: Y - 3, birthMonth: 3,
      marketRate: 0.10, rateKindOverride: 'market', now: new Date(anchoredAt), giftDays: false,
    });
    expect(anchorPlan.lumps).toEqual([]);
    const expected = futureValue(0, anchorPlan.monthly, 3, 0.10, []);

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
    const view = buildGoalPlanView(base({
      type: 'KIDS', config, planAnchoredAt: anchoredAt,
      unitTallies: { 'kid:0:plan529': tally({ tally: anchorPlan.monthly * 12 }) },
    }), ctx({ marketRate: 0.12, now: new Date(2026, 1, 15) }));
    expect(view.status.kind).toBe('on_plan');
    expect(view.status.net).toBe(0);
    expect(view.kids![0].perKid[0].planValue).toBeCloseTo(expected, 6);
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

  it('adds unfinished first-year plans to investMonthly (the invested units), one row per kid with a fund', () => {
    const goal = fundGoal([
      fundKid('A', null, null, [2029, 11], 20800),
      fundKid('B', Y + 1, 5, [2027, 5], 25700),
      { label: 'C', birthYear: Y - 4, birthMonth: 2, accounts: { plan529: account(), brokerage: account() } },
    ], { plan529Target: 100_000, brokerageTarget: 250_000 });
    const view = buildGoalPlanView(goal, ctx());
    const accountsTotal = view.kids!.reduce((sum, a) => sum + a.totals.monthly, 0);
    expect(view.firstYear!.rows.length).toBe(2);
    expect(view.firstYear!.rows.map(r => r.kidIndex)).toEqual([0, 1]);
    // Every baby fund is cash now: $20,800 over 36 months at the cash rate.
    expect(view.firstYear!.rows[0].plan.monthly).toBe(545);
    expect(view.firstYear!.rows[1].plan.rateKind).toBe('cash');
    expect(view.firstYear!.totalMonthly).toBe(view.firstYear!.rows[0].plan.monthly + view.firstYear!.rows[1].plan.monthly);
    expect(view.investMonthly).toBe(accountsTotal + view.firstYear!.totalMonthly);
  });

  it('counts a fund down from its stored due date when viewed six months later', () => {
    const goal = fundGoal([fundKid('A', null, null, [2029, 5], 25700)], noTargets, { planAnchoredAt: '2026-05-15T10:00:00' });
    const view = buildGoalPlanView(goal, ctx());
    expect(view.firstYear!.rows[0].plan.n).toBe(30);
    expect(view.firstYear!.rows[0].plan.rateKind).toBe('cash');
  });

  it('plans every baby fund as cash: a fresh undecided fund and a 24-month fund alike', () => {
    const goal = fundGoal([
      fundKid('A', null, null, [2029, 11], 20800),
      fundKid('B', 2028, 11, [2028, 11], 20800),
    ], noTargets);
    const rows = buildGoalPlanView(goal, ctx()).firstYear!.rows;
    expect(rows[0].plan.rateKind).toBe('cash');
    expect(rows[0].plan.rate).toBe(CASH_RATE);
    expect(rows[1].plan.rateKind).toBe('cash');
    expect(rows[1].plan.rate).toBe(CASH_RATE);
  });

  it('keeps a baby fund in cash whether or not a re-anchor is pending (it no longer reads its distance at the anchor)', () => {
    // Anchored May 2026 the 2029-05 fund was 36 months out; from now it is 30. Cash both ways.
    const goal = fundGoal([fundKid('A', null, null, [2029, 5], 25700)], noTargets, { planAnchoredAt: '2026-05-15T10:00:00' });
    expect(buildGoalPlanView(goal, ctx()).firstYear!.rows[0].plan.rateKind).toBe('cash');
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

  it('kids plans are monthly only: a stored giftDays flag posts no New Year lump to any account', () => {
    const kid = (): KidInput => ({ label: 'A', birthYear: null, birthMonth: null, accounts: { plan529: account(), brokerage: account() } });
    const flagged = buildGoalPlanView(base({ type: 'KIDS', config: { plan529Target: 47_800, brokerageTarget: 100_000, giftDays: true, kids: [kid()] } }), ctx());
    const plain = buildGoalPlanView(base({ type: 'KIDS', config: { plan529Target: 47_800, brokerageTarget: 100_000, kids: [kid()] } }), ctx());
    for (const a of flagged.kids!) {
      expect(a.perKid[0].plan.newYearLump).withContext(a.account).toBe(0);
      expect(a.perKid[0].plan.lumps).withContext(a.account).toEqual([]);
    }
    expect(flagged.monthlyTotal).toBe(plain.monthlyTotal);
    expect((flagged as any).giftDays).toBeUndefined();
  });

  it('values a cash fund beside a market 529 at the cash rate', () => {
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
    ], { plan529Target: 47_800, brokerageTarget: 0 }, {
      planAnchoredAt: anchoredAt,
      unitTallies: { 'kid:0:plan529': tally({ tally: bal529 }), 'kid:0:firstYear': tally({ tally: balFund }) },
    });
    const view = buildGoalPlanView(goal, ctx({ now: new Date(2028, 9, 15) }));
    expect(view.status.kind).toBe('on_plan');
    expect(view.firstYear!.rows[0].planValue).toBeCloseTo(balFund, 6);
    expect(view.kids![0].perKid[0].planValue).toBeCloseTo(bal529, 6);
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
    ], { plan529Target: 47_800, brokerageTarget: 0 }, {
      planAnchoredAt: anchoredAt,
      // The finished fund's own figures would read Behind; a finished fund no longer counts.
      unitTallies: {
        'kid:0:plan529': tally({ tally: bal529 }),
        'kid:0:firstYear': tally({ tally: 20_000, net: -800, netSince: '2027-10-01', creditsUntil: '2027-11-30' }),
      },
    });
    const view = buildGoalPlanView(goal, ctx({ now: new Date(2028, 4, 15) }));
    expect(view.firstYear!.rows[0].plan.finished).toBeTrue();
    expect(view.status.kind).toBe('on_plan');
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
    expect(withFund.progressPct).toBe(noFund.progressPct);
    expect(withFund.status).toEqual(noFund.status);
    expect(withFund.firstYear!.rows.map(r => r.plan.finished)).toEqual([true, true, true]);
    expect(withFund.firstYear!.rows.map(r => r.hidden)).toEqual([false, true, false]);
    expect(withFund.firstYear!.rows[0].plan.monthly).toBe(0);
  });

  it('says Ready for the first year and never 18th birthday for a fund-only goal (a cash plan: baby funds are cash)', () => {
    const goal = fundGoal([fundKid('A', null, null, [2029, 11], 20800)], noTargets);
    const view = buildGoalPlanView(goal, ctx());
    expect(view.headline).toBe('Ready for the first year');
    expect(view.subtitle).toBe('$545/mo for A');
    expect(view.kids).toEqual([]);
    expect(view.rateKind).toBe('cash');
    expect(view.status.kind).toBe('started');

    const two = fundGoal([fundKid('A', null, null, [2029, 11], 20800), fundKid('B', null, null, [2029, 11], 20800)], noTargets);
    expect(buildGoalPlanView(two, ctx()).subtitle).toBe('$1,090/mo for both kids');
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
  it('reads Done for a simple goal past its last credit day with nothing missed', () => {
    const goal = base({
      config: { targetAmount: 12_000, targetYear: 2026 },
      currentBalance: 11_990, anchorBalance: 0, anchorMonthly: 1_100, planRate: 0,
      planAnchoredAt: '2026-01-15T00:00:00',
      tally: tally({ tally: 11_990, creditsUntil: '2026-12-31', nextCreditDate: null, nextCreditAmount: 0 }),
    });
    const view = buildGoalPlanView(goal, ctx({ now: new Date(2027, 5, 20) }));
    expect(view.status.kind).toBe('done');
  });

  it('stops a kid account plan value at its deadline', () => {
    // One account, $100 a month for the 12 months to the 18th birthday in Jan 2027, viewed 18 months in.
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
    // Under 36 months to go at the anchor, so the account is cash and grows at the cash rate.
    expect(view.kids![0].perKid[0].planValue).toBeCloseTo(futureValue(0, 100, 12, CASH_RATE, []), 6);
    expect(view.kids![0].perKid[0].planValue).toBeLessThan(futureValue(0, 100, 18, CASH_RATE, []));
  });

  it('shows what is due in the deadline month for a simple goal', () => {
    const goal = base({ config: { targetAmount: 30_000, targetYear: 2026 }, currentBalance: 24_000, anchorBalance: 24_000, planAnchoredAt: '2026-12-01T00:00:00' });
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
        kids: [{ label: 'Teen', birthYear: 2008, birthMonth: 12, accounts: { plan529: account(30_000, { anchorBalance: 30_000 }), brokerage: account(0) } }],
      } as KidsConfig,
      planAnchoredAt: '2026-12-01T00:00:00',
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
    // The 529 alone: no monthly planned, so $1,000 of its $100,000 target. The zero-target brokerage
    // and its $5,000 are left out.
    expect(view.progressPct).toBeCloseTo(1, 6);
  });
});

// ── One-screen plan page: hero copy, timeline, pills, gift days, How FRED helps ──

describe('buildGoalPlanView one-screen page', () => {
  const ANCHOR = '2026-11-15T00:00:00';
  // Goal money never lives in FRED (Andy, 2026-11-11): the note names the accounts and says FRED keeps score.
  const FREEDOM = 'FRED\'s own investing is your Freedom Plan, paycheck by paycheck.';
  const KIDS_HELP = (accounts: string) => `FRED works out the amounts and keeps score. The money lives in accounts you open: ${accounts}. ${FREEDOM}`;
  const SIMPLE_HELP = `FRED works out the amount and keeps score. The money lives in an account you open. ${FREEDOM}`;

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

  it('KIDS subtitle reads "$X/mo for all 3 kids" (the whole month), the hero reads Family plan, and audience uses a single kid label', () => {
    const view = buildGoalPlanView(sample(), ctx());
    expect(view.audience).toBe('for all 3 kids');
    expect(view.subtitle).toBe(`${fmtMoney(view.monthlyTotal)}/mo for all 3 kids`);
    expect(view.heroLabel).toBe('Family plan');

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

  it('pins the sample family figures: the old $2,249 hero is now investMonthly ($2,287 with cash baby funds), the hero is the whole month with Ellie\'s $1,910', () => {
    const off = buildGoalPlanView(sample(), ctx());
    expect(off.heroLabel).toBe('Family plan');
    expect(off.audience).toBe('for all 3 kids');
    // $47,800 college and $100,000 nest egg per kid, baby funds for the two unborn, 10%, biweekly.
    // The invested units were $2,249 with the undated kid's fund invested; every baby fund is cash
    // now, so they ask $2,287. The hero adds Ellie (2, the only kid at home) at ~$1,910.
    expect(Math.round(off.investMonthly)).toBe(2_287);
    expect(off.everyday!.map(e => e.monthly)).toEqual([1_910, 0, 0]);
    expect(Math.round(off.monthlyTotal)).toBe(4_197);
    expect(Math.round(off.perPaycheck!)).toBe(1_934);
    expect(off.subtitle).toBe('$4,197/mo for all 3 kids');
  });

  it('pills: college and nest egg are Invested at 18 years out, a baby fund is always Cash', () => {
    const view = buildGoalPlanView(family([kid('Theo', 2028, 6, { due: [2028, 6], target: 20_800 })]), ctx());
    for (const acct of view.kids!) expect(acct.perKid[0].pill).withContext(acct.account).toBe('Invested');
    expect(view.firstYear!.rows[0].plan.n).toBe(19);
    expect(view.firstYear!.rows[0].pill).toBe('Cash');
    expect(view.pill).toBeUndefined();

    // A kid within 36 months of 18 is Cash; an undated kid's fund (36-month runway) is Cash too.
    const mixed = buildGoalPlanView(family([
      kid('Teen', Y - 16, 6),
      kid('', null, null, { due: [2029, 11], target: 20_800 }),
    ]), ctx());
    for (const acct of mixed.kids!) {
      expect(acct.perKid[0].pill).toBe('Cash');
      expect(acct.perKid[1].pill).toBe('Invested');
    }
    expect(mixed.firstYear!.rows[0].pill).toBe('Cash');

    // Simple goals: one pill on the view.
    expect(buildGoalPlanView(base({ type: 'HOUSE', config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6 } }), ctx()).pill).toBe('Invested');
    expect(buildGoalPlanView(base({ type: 'CAR', config: { price: 40_000, downPaymentPct: null, targetYear: Y + 1 } }), ctx()).pill).toBe('Cash');
    expect(buildGoalPlanView(base({ type: 'EMERGENCY_FUND', config: { monthlyExpenses: 4_000, months: 6, targetYear: Y + 5 } }), ctx()).pill).toBe('Cash');
    expect(buildGoalPlanView(base({ type: 'VACATION', config: { tripsPerYear: 2, budgetPerTrip: 2_000, savedThisYear: 0, savedYear: Y } }), ctx()).pill).toBe('Cash');
  });

  it('howFredHelps names the accounts the money lives in, outside the app: all invested, mixed (names the baby funds), all cash (vacation)', () => {
    const invested = buildGoalPlanView(family([kid('A', 2020, 3), kid('B', 2023, 8)]), ctx());
    expect(invested.howFredHelps).toBe(KIDS_HELP('a 529 for college, a brokerage account for the nest egg'));
    expect(invested.howFredHelps).not.toContain('portfolio');

    const oneFund = buildGoalPlanView(family([kid('Theo', 2028, 6, { due: [2028, 6], target: 20_800 })]), ctx());
    expect(oneFund.howFredHelps).toBe(KIDS_HELP('a 529 for college, a brokerage account for the nest egg, cash for the baby fund'));

    const twoFunds = buildGoalPlanView(family([
      kid('Theo', 2028, 6, { due: [2028, 6], target: 20_800 }),
      kid('Mia', 2028, 9, { due: [2028, 9], target: 16_500 }),
    ]), ctx());
    expect(twoFunds.howFredHelps).toBe(KIDS_HELP('a 529 for college, a brokerage account for the nest egg, cash for the baby funds'));

    // A cash unit that is not a baby fund (a kid within 36 months of 18) still lives in its 529.
    const teen = buildGoalPlanView(family([kid('Teen', Y - 16, 6), kid('B', 2023, 8)]), ctx());
    expect(teen.howFredHelps).toBe(KIDS_HELP('a 529 for college, a brokerage account for the nest egg'));
    const collegeOnly = buildGoalPlanView(family([kid('A', 2020, 3)], { brokerageTarget: 0 }), ctx());
    expect(collegeOnly.howFredHelps).toBe(KIDS_HELP('a 529 for college'));

    const cash = 'Keep this in cash, like a high-yield savings account, ready the day you need it. FRED tracks it here; the money stays outside the app.';
    expect(buildGoalPlanView(base({ type: 'VACATION', config: { tripsPerYear: 2, budgetPerTrip: 2_000, savedThisYear: 0, savedYear: Y } }), ctx()).howFredHelps).toBe(cash);
    expect(buildGoalPlanView(base({ type: 'EMERGENCY_FUND', config: { monthlyExpenses: 4_000, months: 6, targetYear: Y + 2 } }), ctx()).howFredHelps).toBe(cash);
    expect(buildGoalPlanView(family([kid('Teen', Y - 16, 6)]), ctx()).howFredHelps).toBe(cash);
    const house = buildGoalPlanView(base({ type: 'HOUSE', config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6 } }), ctx());
    expect(house.howFredHelps).toBe(SIMPLE_HELP);
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
  // Shortened 2026-11-11 (Andy): the card carries the detail, the note carries the reason.
  const KIDS_WHY = new RegExp('^' + escape(
    'College and the nest egg come due at 18, and those years do the work: of the ',
  ) + `(${MONEY})` + escape(' this plan reaches, ~') + `(${MONEY})` + escape(' is from you and ~')
    + `(${MONEY})` + escape(
    ' is growth. Pull it out early and growth stops. Money you need within three years is planned as cash.',
  ) + '$');
  const SIMPLE_WHY = new RegExp('^' + escape(
    'You will not spend this for years, and those years do the work: of the ',
  ) + `(${MONEY})` + escape(' this plan reaches, ~') + `(${MONEY})` + escape(' is from you and ~')
    + `(${MONEY})` + escape(' is growth. Pull it out early and growth stops.') + '$');
  const dollars = (s: string) => Number(s.replace(/[$,]/g, ''));
  const EVERYDAY_LEAD = 'Everyday is what each kid costs at their age, by FRED\'s estimate';
  it('coversLine speaks in the card\'s terms (shortened 2026-11-11): Invest names the units, the baby fund is year one, Everyday prices the kids at home aged 1 to 17 from the everyday entries', () => {
    // Ellie is 2, Theo arrives in 2028, the third kid is undated: one kid at home (only-child factor), two funds.
    expect(buildGoalPlanView(sample(), ctx()).coversLine).toBe(
      'Invest is college and the nest egg; the baby funds are year one of everyday costs, saved ahead. '
      + `${EVERYDAY_LEAD}: ~$1,910 a month for Ellie (2). A kid who has not arrived costs nothing yet.`,
    );

    // Two at home, ages 6 (6-8 band) and 3 (3-5 band), two-kid factor 1.00: (12,350 + 12,730) x inflation / 12.
    const two = buildGoalPlanView(family([kid('A', 2020, 3), kid('B', 2023, 8)]), ctx());
    expect(two.coversLine).toBe(`Invest is college and the nest egg. ${EVERYDAY_LEAD}: ~$2,970 a month for A (6) and B (3).`);

    const collegeOnly = buildGoalPlanView(family([kid('A', 2020, 3)], { brokerageTarget: 0 }), ctx());
    expect(collegeOnly.coversLine).toBe(`Invest is college. ${EVERYDAY_LEAD}: ~$1,860 a month for A (6).`);

    // Mae is under one with no baby fund: at home but not priced, so the line says when costs start.
    const newborn = buildGoalPlanView(family([kid('Mae', 2026, 10)], { plan529Target: 0 }), ctx());
    expect(newborn.coversLine).toBe(`Invest is the nest egg. ${EVERYDAY_LEAD}; costs start at the first birthday.`);

    // Mae under one WITH a baby fund: the fund covers year one.
    const funded = buildGoalPlanView(
      family([kid('Mae', 2026, 10, { due: [2027, 10], target: 16_500 })], { plan529Target: 0 }),
      ctx(),
    );
    expect(funded.coversLine).toBe(
      `Invest is the nest egg; the baby fund is year one of everyday costs, saved ahead. ${EVERYDAY_LEAD}; the baby fund covers year one.`,
    );

    // A kid under one beside a priced kid: only the priced kid is named, sized as one of two at home.
    const withBaby = buildGoalPlanView(family([kid('Mae', 2026, 10), kid('A', 2020, 3)], { brokerageTarget: 0 }), ctx());
    expect(withBaby.coversLine).toBe(`Invest is college. ${EVERYDAY_LEAD}: ~${fmtMoney(everydayMonthlyForKid(6, 2))} a month for A (6).`);

    const fundOnly = buildGoalPlanView(
      family([kid('Theo', 2028, 6, { due: [2028, 6], target: 20_800 })], { plan529Target: 0, brokerageTarget: 0 }),
      ctx(),
    );
    expect(fundOnly.coversLine).toBe(
      `Invest is the baby fund: year one of everyday costs, saved ahead. ${EVERYDAY_LEAD}; nothing yet until the first kid arrives.`,
    );
  });

  it('coversLine reads its everyday figure from the everyday entries, so it always matches the hero (moved off its own age and price math)', () => {
    const view = buildGoalPlanView(family([kid('A', 2020, 3), kid('B', 2023, 8), kid('Mae', 2026, 10)]), ctx());
    const priced = view.everyday!.filter(e => e.state === 'priced');
    expect(priced.length).toBe(2);
    const sum = priced.reduce((s, e) => s + e.monthly, 0);
    expect(sum).toBe(view.monthlyTotal - view.investMonthly);
    expect(view.coversLine).toContain(`${EVERYDAY_LEAD}: ~${fmtMoney(sum)} a month for A (6) and B (3).`);
  });

  it('whyInvestLine names only the invested units the family has', () => {
    const collegeOnly = buildGoalPlanView(family([kid('A', 2020, 3)], { brokerageTarget: 0 }), ctx());
    expect(collegeOnly.whyInvestLine).toMatch(/^College comes due at 18, and those years do the work: of the /);
    const nestOnly = buildGoalPlanView(family([kid('A', 2020, 3)], { plan529Target: 0 }), ctx());
    expect(nestOnly.whyInvestLine).toMatch(/^The nest egg comes due at 18, and those years do the work: of the /);
  });

  it('whyInvestLine for the sample family: from you plus growth plus balances is what the invested units reach (no baby fund: they are cash)', () => {
    const view = buildGoalPlanView(sample(), ctx());
    expect(view.whyInvestLine).not.toBeNull();
    const m = view.whyInvestLine!.match(KIDS_WHY);
    expect(m).withContext(view.whyInvestLine!).not.toBeNull();
    const [reaches, fromYou, fromGrowth] = [m![1], m![2], m![3]].map(dollars);
    expect(fromYou % 1_000).toBe(0);
    expect(fromGrowth % 1_000).toBe(0);
    expect(fromGrowth).toBeGreaterThan(0);

    // The invested units: every 529 and brokerage plan here. Both baby funds are cash.
    const targetOf = { plan529: 47_800, brokerage: 100_000 };
    const invested: Array<{ target: number; balance: number }> = [
      ...view.kids!.reduce<Array<{ target: number; balance: number }>>((all, a) => all.concat(a.perKid
        .filter(k => k.plan.rateKind === 'market')
        .map(k => ({ target: targetOf[a.account], balance: k.balance }))), []),
      ...view.firstYear!.rows
        .filter(r => r.plan.rateKind === 'market' && !r.plan.finished)
        .map(r => ({ target: r.plan.target, balance: r.balance })),
    ];
    // Three kids' college and nest egg.
    expect(invested.length).toBe(6);
    expect(reaches).toBe(invested.reduce((sum, u) => sum + u.target, 0));
    expect(reaches).toBe(3 * (47_800 + 100_000));
    // The two shares add up to what the plan reaches (what is already saved counts as "from you").
    expect(Math.abs(fromYou + fromGrowth - reaches)).toBeLessThanOrEqual(1_000);
  });

  it('whyInvestLine counts a saved balance as from you, so the shares still add up', () => {
    const one = kid('A', 2020, 3);
    one.accounts.brokerage = account(25_000, { anchorBalance: 25_000 });
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
        kids: [{ label: 'Teen', birthYear: 2008, birthMonth: 12, accounts: { plan529: account(30_000, { anchorBalance: 30_000 }), brokerage: account(0) } }],
      } as KidsConfig,
      planAnchoredAt: '2026-12-01T00:00:00',
    });
    const k = buildGoalPlanView(kids, ctx({ now: new Date(2026, 11, 5) }));
    expect(k.heroLabel).toBe('Due now');
    expect(k.coversLine).toBeNull();
    expect(k.whyInvestLine).toBeNull();

    const simple = buildGoalPlanView(
      base({ config: { targetAmount: 30_000, targetYear: 2026 }, currentBalance: 24_000, anchorBalance: 24_000, planAnchoredAt: '2026-12-01T00:00:00' }),
      ctx({ now: new Date(2026, 11, 5) }),
    );
    expect(simple.heroLabel).toBe('Due now');
    expect(simple.coversLine).toBeNull();
    expect(simple.whyInvestLine).toBeNull();
  });

  it('a HOUSE plan at the market rate reads the simple-plan first sentence and its loanLine is the mortgage line', () => {
    const view = buildGoalPlanView(
      base({ type: 'HOUSE', currentBalance: 12_000, anchorBalance: 12_000, planAnchoredAt: '2026-11-01T00:00:00', config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6 } }),
      ctx(),
    );
    expect(view.coversLine).toBeNull();
    expect(view.loanLine).toBe('Then the mortgage: ~$2,737 a month at 7.28% over 30 years, before taxes and insurance.');
    const m = view.whyInvestLine!.match(SIMPLE_WHY);
    expect(m).withContext(view.whyInvestLine!).not.toBeNull();
    const [reaches, fromYou, fromGrowth] = [m![1], m![2], m![3]].map(dollars);
    expect(reaches).toBe(100_000);
    // The $12,000 already saved is "from you" too.
    expect(fromYou).toBe(Math.round((view.simple!.fromYou + 12_000) / 1_000) * 1_000);
    expect(fromGrowth).toBe(Math.round(view.simple!.fromGrowth / 1_000) * 1_000);
  });

  it('house subtitle leads with the down payment and the mortgage line', () => {
    const house = buildGoalPlanView(base({
      type: 'HOUSE',
      config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6, loanRate: 6.5 },
    }), ctx());
    expect(house.subtitle).toMatch(/^\$100,000 down · \$[\d,]+\/mo for your home$/);
    expect(house.loanLine).toBe('Then the mortgage: ~$2,528 a month at 6.5% over 30 years, before taxes and insurance.');
  });

  it('car target is the dollar down payment', () => {
    const car = buildGoalPlanView(base({
      type: 'CAR',
      config: { price: 40_000, downPaymentPct: null, downPaymentAmount: 5_000, loanRate: 7.5, targetYear: Y + 4 },
    }), ctx());
    expect(car.simple!.target).toBe(5_000);
    expect(car.subtitle).toMatch(/^\$5,000 down · \$[\d,]+\/mo for your car$/);
    expect(car.loanLine).toBe('Then the loan: ~$701 a month at 7.5% over 60 months.');
  });

  it('a fractional down payment is rounded to whole dollars in the subtitle', () => {
    // $32,333 x 20% = $6,466.60, shown as $6,467.
    const house = buildGoalPlanView(base({
      type: 'HOUSE',
      config: { price: 32_333, downPaymentPct: 20, targetYear: Y + 6 },
    }), ctx());
    expect(house.subtitle).toMatch(/^\$6,467 down · /);
  });

  it('a due-now house plan keeps its loan line and source', () => {
    const house = buildGoalPlanView(base({
      type: 'HOUSE',
      currentBalance: 0, anchorBalance: 0, planAnchoredAt: `${Y}-01-01T00:00:00`,
      config: { price: 500_000, downPaymentPct: 20, targetYear: Y - 1, loanRate: 6.5 },
    }), ctx());
    expect(house.heroLabel).toBe('Due now');
    expect(house.loanLine).toContain('Then the mortgage:');
    expect(house.loanSource).toBe('Default rate: Freddie Mac PMMS, 7.28% as of November 19, 2026.');
  });

  it('legacy car without downPaymentAmount still targets price × pct', () => {
    const car = buildGoalPlanView(base({
      type: 'CAR',
      config: { price: 40_000, downPaymentPct: 25, targetYear: Y + 4 },
    }), ctx());
    expect(car.simple!.target).toBe(10_000);
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

  // ── The kids' whole month: invested units plus what each kid at home costs ──

  describe('everyday line', () => {
    it('prices each kid at home and leaves the rest at 0: unborn, year one, priced, grown', () => {
      // Expected Mar 2027 (unborn); born this month (year one); born Mar 2024 (priced, 2); born Jan 2008 (grown, 18).
      const view = buildGoalPlanView(family([
        kid('Ivy', Y + 1, 3, { due: [Y + 2, 3], target: 16_500 }),
        kid('Mae', Y, 11),
        kid('Ellie', 2024, 3),
        kid('Sam', 2008, 1),
      ]), ctx());
      expect(view.everyday!.map(e => e.state)).toEqual(['unborn', 'year_one', 'priced', 'grown']);
      // Two kids at home (Mae and Ellie) size Ellie's figure.
      expect(view.everyday!.map(e => e.monthly)).toEqual([0, 0, everydayMonthlyForKid(2, 2), 0]);
      expect(view.everyday![2].monthly).toBeGreaterThan(0);
      expect(view.everyday![0]).toEqual(jasmine.objectContaining({
        label: 'Everyday', subLine: '', fromLine: 'From Mar 2027: the baby fund is year one',
      }));
      // Mae has no baby fund, so her everyday costs simply start at one.
      expect(view.everyday![1]).toEqual(jasmine.objectContaining({
        label: 'Everyday', subLine: '', fromLine: 'Everyday costs start at the first birthday',
      }));
      expect(view.everyday![2]).toEqual(jasmine.objectContaining({
        label: 'Age 2 costs', subLine: 'Food, clothes, diapers and childcare', fromLine: null,
      }));
      expect(view.everyday![3]).toEqual(jasmine.objectContaining({ label: 'Everyday', subLine: '', fromLine: null }));
    });

    it('a kid under one with a baby fund reads "The baby fund covers year one"; without one, "Everyday costs start at the first birthday" (no trailing period)', () => {
      const view = buildGoalPlanView(family([
        kid('Mae', Y, 11, { due: [Y + 1, 11], target: 16_500 }),
        kid('Bo', Y, 11),
      ]), ctx());
      expect(view.everyday!.map(e => e.state)).toEqual(['year_one', 'year_one']);
      expect(view.everyday![0].fromLine).toBe('The baby fund covers year one');
      expect(view.everyday![1].fromLine).toBe('Everyday costs start at the first birthday');
    });

    it('an unborn kid with no month, an undated kid, and one expected this year with no month read "From the day they arrive"', () => {
      const view = buildGoalPlanView(family([
        kid('A', Y + 1, null, { due: [Y + 2, 12], target: 16_500 }),
        kid('B', null, null, { due: [Y + 3, 11], target: 16_500 }),
        kid('C', Y, null, { due: [Y + 1, 12], target: 16_500 }),
      ]), ctx());
      expect(view.everyday!.map(e => e.state)).toEqual(['unborn', 'unborn', 'unborn']);
      for (const e of view.everyday!) {
        expect(e.fromLine).toBe('From the day they arrive: the baby fund is year one');
        expect(e.monthly).toBe(0);
      }
    });

    it('an unborn kid whose baby fund is off promises no fund: "everyday costs start at the first birthday"', () => {
      const view = buildGoalPlanView(family([
        kid('Ivy', Y + 1, 3),
        kid('A', Y + 1, null),
        kid('B', null, null),
        kid('C', Y, null),
      ]), ctx());
      expect(view.everyday!.map(e => e.state)).toEqual(['unborn', 'unborn', 'unborn', 'unborn']);
      expect(view.everyday!.map(e => e.fromLine)).toEqual([
        'From Mar 2027: everyday costs start at the first birthday',
        'From the day they arrive: everyday costs start at the first birthday',
        'From the day they arrive: everyday costs start at the first birthday',
        'From the day they arrive: everyday costs start at the first birthday',
      ]);
      for (const e of view.everyday!) expect(e.fromLine).not.toContain('baby fund');
    });

    it('no from line ends with a period, in any state', () => {
      const view = buildGoalPlanView(family([
        kid('Ivy', Y + 1, 3, { due: [Y + 2, 3], target: 16_500 }),
        kid('Jo', Y + 1, 3),
        kid('Mae', Y, 11, { due: [Y + 1, 11], target: 16_500 }),
        kid('Bo', Y, 11),
        kid('Ellie', 2024, 3),
        kid('Sam', 2008, 1),
      ]), ctx());
      for (const e of view.everyday!) {
        if (e.fromLine !== null) expect(e.fromLine).not.toMatch(/\.$/);
      }
    });

    it('the hero is the whole month: investMonthly plus the everyday sum, labeled Family plan', () => {
      const view = buildGoalPlanView(sample(), ctx());
      const everyday = view.everyday!.reduce((s, e) => s + e.monthly, 0);
      expect(everyday).toBeGreaterThan(0);
      expect(view.monthlyTotal).toBe(view.investMonthly + everyday);
      expect(view.heroLabel).toBe('Family plan');
      expect(view.perPaycheck).toBe(InvestmentFrequencyUtils.toPerPaycheckEquivalent(view.monthlyTotal, 'biweekly'));
      expect(view.subtitle).toContain(`${fmtMoney(view.monthlyTotal)}/mo`);
    });

    it('How FRED helps quotes no figure at all since 2026-11-11 (the hero carries it), so the everyday figure can never leak into it', () => {
      const view = buildGoalPlanView(sample(), ctx());
      expect(view.investMonthly).toBeLessThan(view.monthlyTotal);
      expect(view.howFredHelps).not.toMatch(/\$\d/);
      expect(view.howFredHelps).not.toContain(fmtMoney(view.monthlyTotal));
    });

    it('invested units due now still read "$X due now" with a kid at home', () => {
      // Teen turns 18 this month with $20,000 to go; B (6) already has a full 529, so nothing is left to spread.
      const config: KidsConfig = {
        plan529Target: 50_000, brokerageTarget: 0,
        kids: [
          { label: 'Teen', birthYear: 2008, birthMonth: 12, accounts: { plan529: account(30_000, { anchorBalance: 30_000 }), brokerage: account(0) } },
          { label: 'B', birthYear: 2020, birthMonth: 3, accounts: { plan529: account(50_000, { anchorBalance: 50_000 }), brokerage: account(0) } },
        ],
      };
      // Anchored this month, so the plan's value is the anchor balance with no growth yet.
      const view = buildGoalPlanView(base({ type: 'KIDS', config, planAnchoredAt: '2026-12-01T00:00:00' }), ctx({ now: new Date(2026, 11, 5) }));
      expect(view.investMonthly).toBe(0);
      expect(view.everyday![1].monthly).toBeGreaterThan(0);
      expect(view.heroLabel).toBe('Due now');
      expect(view.subtitle).toBe('$20,000 due now for both kids');
    });

    it('the baby fund is always cash, even five years out', () => {
      const view = buildGoalPlanView(family([kid('Theo', Y + 5, 6, { due: [Y + 5, 6], target: 20_800 })]), ctx());
      expect(view.firstYear!.rows[0].plan.rateKind).toBe('cash');
      expect(view.firstYear!.rows[0].plan.rate).toBe(CASH_RATE);
      expect(view.firstYear!.rows[0].pill).toBe('Cash');
    });

    it('simple goals: investMonthly equals monthlyTotal and heroLabel stays Save this month', () => {
      const view = buildGoalPlanView(base({ type: 'HOUSE', config: { price: 500_000, downPaymentPct: 20, targetYear: Y + 6 } }), ctx());
      expect(view.investMonthly).toBe(view.monthlyTotal);
      expect(view.heroLabel).toBe('Save this month');
      expect(view.everyday).toBeUndefined();

      const vacation = buildGoalPlanView(base({
        type: 'VACATION',
        config: { tripsPerYear: 4, budgetPerTrip: 2_500, savedThisYear: 0, savedYear: Y },
      }), ctx());
      expect(vacation.investMonthly).toBe(vacation.monthlyTotal);
      expect(vacation.everyday).toBeUndefined();
    });
  });
});

// ── Saved so far: the server's tally drives status, progress and the band ────

describe('buildGoalPlanView from the tally', () => {
  it('simple view re-solves from planValue, not the tally', () => {
    const anchoredAt = new Date(2025, 10, 15);
    const anchorPlan = proposeSimplePlan({ target: 200_000, balance: 0, targetYear: Y + 6, marketRate: 0.10, forceCash: false, rateKindOverride: null, now: anchoredAt });
    const saved = anchorPlan.monthly * 12;
    const goal = base({
      anchorMonthly: anchorPlan.monthly, planAnchoredAt: '2025-11-15T00:00:00', currentBalance: saved,
      tally: tally({ tally: saved }),
    });
    const view = buildGoalPlanView(goal, ctx({ rateKindOverride: 'market' }));
    expect(Math.abs(view.investMonthly - anchorPlan.monthly)).toBeLessThanOrEqual(1);
    // Solving from the money put in (no growth) would ask for more every month.
    const fromTally = proposeSimplePlan({ target: 200_000, balance: saved, targetYear: Y + 6, marketRate: 0.10, forceCash: false, rateKindOverride: 'market', now: NOW });
    expect(fromTally.monthly).toBeGreaterThan(anchorPlan.monthly + 1);
  });

  it('a missed tap raises the re-solved monthly', () => {
    const anchorPlan = proposeSimplePlan({ target: 200_000, balance: 0, targetYear: Y + 6, marketRate: 0.10, forceCash: false, rateKindOverride: null, now: new Date(2025, 10, 15) });
    const goal = (net: number) => base({ anchorMonthly: anchorPlan.monthly, planAnchoredAt: '2025-11-15T00:00:00', tally: tally({ tally: 5_000, net }) });
    expect(buildGoalPlanView(goal(-450), ctx()).investMonthly).toBeGreaterThan(buildGoalPlanView(goal(0), ctx()).investMonthly);
  });

  it('keeps the draft balance in a preview with no anchor yet', () => {
    const goal = base({ currentBalance: 24_000, anchorBalance: 0 });
    const preview = buildGoalPlanView(goal, ctx({ reanchorPending: true }));
    const plan = proposeSimplePlan({ target: 200_000, balance: 24_000, targetYear: Y + 6, marketRate: 0.10, forceCash: false, rateKindOverride: null, now: NOW });
    expect(preview.investMonthly).toBe(plan.monthly);
    expect(preview.growthLine).toBeNull();
  });

  describe('tallyLine', () => {
    // $1,200 a month for the 25 months from Nov 2026 through Dec 2028: your part is $30,000.
    const goal = (t: Partial<UnitTally>) => base({
      config: { targetAmount: 40_000, targetYear: 2028 },
      anchorMonthly: 1_200, planAnchoredAt: '2026-11-01T00:00:00',
      tally: tally({ tally: 900, nextCreditDate: '2027-01-01', ...t }),
    });

    it('tallyLine reads Saved $900 · 3% of your $30K · Next $450 Jan 1', () => {
      const view = buildGoalPlanView(goal({}), ctx());
      expect(view.tallyLine).toEqual({ saved: 'Saved $900', part: '3% of your $30K', next: 'Next $450 Jan 1', net: null });
      expect(view.progressPct).toBeCloseTo(3, 6);
      expect(view.status.kind).toBe('on_plan');
    });

    it('reads Behind by $450 since Nov 15 after a missed tap, and Ahead by $450 after a doubled up', () => {
      const behind = buildGoalPlanView(goal({ tally: 450, net: -450, netSince: '2026-11-15' }), ctx());
      expect(behind.tallyLine!.net).toBe('Behind by $450 since Nov 15');
      expect(behind.status.kind).toBe('behind');
      expect(buildGoalPlanView(goal({ tally: 1_350, net: 450 }), ctx()).tallyLine!.net).toBe('Ahead by $450');
    });

    it('reads Saved $0 · First $450 lands Dec 1 before the first credit', () => {
      const view = buildGoalPlanView(goal({ tally: 0, startedCrediting: false, creditedThrough: null, nextCreditDate: '2026-12-01' }), ctx());
      expect(view.tallyLine).toEqual({ saved: 'Saved $0', part: 'First $450 lands Dec 1', next: null, net: null });
      expect(view.status.kind).toBe('started');
    });

    it('has no next fragment once credits have stopped', () =>
      expect(buildGoalPlanView(goal({ nextCreditDate: null, nextCreditAmount: 0 }), ctx()).tallyLine!.next).toBeNull());

    it('kids carry no goal-level tallyLine', () =>
      expect(buildGoalPlanView(kidsGoal([{ label: 'A', birthYear: Y - 2, birthMonth: 3, accounts: { plan529: account(), brokerage: account() } }]), ctx()).tallyLine).toBeNull());
  });

  it('kids status is the worst unit', () => {
    const kid: KidInput = { label: 'Ada', birthYear: Y - 2, birthMonth: 3, accounts: { plan529: account(0, { anchorMonthly: 200 }), brokerage: account(0, { anchorMonthly: 300 }) } };
    const view = (brokerage: Partial<UnitTally>) => buildGoalPlanView(kidsGoal([kid], {
      unitTallies: { 'kid:0:plan529': tally({ tally: 400, nextCreditAmount: 100 }), 'kid:0:brokerage': tally({ tally: 600, ...brokerage }) },
    }), ctx());
    const behind = view({ net: -150, netSince: '2026-11-15' }).status;
    expect(behind).toEqual({ kind: 'behind', net: -150, netSince: '2026-11-15' });
    expect(view({ net: 150 }).status.kind).toBe('ahead');
    expect(view({}).status.kind).toBe('on_plan');
    expect(view({ tally: 0, startedCrediting: false }).status.kind).toBe('on_plan');
  });

  it("vacation reads Done when the year's budget is in", () => {
    const vacation = (saved: number) => buildGoalPlanView(base({
      type: 'VACATION',
      config: { tripsPerYear: 2, budgetPerTrip: 3_000, savedThisYear: saved, savedYear: Y },
      anchorMonthly: 500,
      tally: tally({ tally: saved, nextCreditDate: saved >= 6_000 ? null : '2026-12-01' }),
    }), ctx());
    const done = vacation(6_000);
    expect(done.status.kind).toBe('done');
    expect(done.progressPct).toBe(100);
    expect(done.tallyLine).toEqual({ saved: 'Saved $6,000', part: '100% of this year\'s $6K', next: null, net: null });
    expect(vacation(2_500).status.kind).toBe('on_plan');
    expect(vacation(2_500).growthLine).toBeNull();
  });

  it('growthLine is set on an invested simple goal years in and null on a cash one', () => {
    // Anchored Nov 2023 at $1,000 a month: 36 months in, the plan values $36,000 put in at the 10% plan rate.
    const invested = buildGoalPlanView(base({
      anchorMonthly: 1_000, planAnchoredAt: '2023-11-15T00:00:00', tally: tally({ tally: 36_000 }),
    }), ctx());
    expect(invested.growthLine).toBe(`Assuming growth at 10%, worth ~${fmtMoney(futureValue(0, 1_000, 36, 0.10, []))} today`);

    const cash = buildGoalPlanView(base({
      type: 'EMERGENCY_FUND', config: { monthlyExpenses: 4_000, months: 6, targetYear: Y + 1 },
      anchorMonthly: 1_000, planRate: CASH_RATE, planRateKind: 'cash', planAnchoredAt: '2023-11-15T00:00:00',
      tally: tally({ tally: 20_000 }),
    }), ctx());
    expect(cash.growthLine).toBeNull();
  });

  it('an imported goal with no plan measures progress against the target', () => {
    const view = buildGoalPlanView(base({
      clientId: 'v2-abc', config: { targetAmount: 20_000, targetYear: Y + 3 },
      currentBalance: 5_000, anchorBalance: 5_000, anchorMonthly: 0, tally: tally({ tally: 5_000 }),
    }), ctx());
    expect(view.status.kind).toBe('imported');
    expect(view.progressPct).toBe(25);
    expect(view.tallyLine!.part).toBe('25% of your $20K');
  });

  it('a plan with money due now measures progress against the target', () => {
    const view = buildGoalPlanView(base({
      config: { targetAmount: 30_000, targetYear: 2026 },
      currentBalance: 24_000, anchorBalance: 24_000, anchorMonthly: 0, planAnchoredAt: '2026-12-01T00:00:00',
      tally: tally({ tally: 24_000, nextCreditDate: null, nextCreditAmount: 0 }),
    }), ctx({ now: new Date(2026, 11, 5) }));
    expect(view.heroLabel).toBe('Due now');
    expect(view.progressPct).toBe(80);
  });

  it('an imported goal has no growth line', () => {
    // Anchored three years ago at $10,000: a planned goal's value has grown well past the tally by now.
    const goal = (clientId: string | null) => base({
      clientId, anchorBalance: 10_000, anchorMonthly: clientId ? 0 : 100, planAnchoredAt: '2023-11-15T00:00:00',
      currentBalance: 10_000, tally: tally({ tally: 10_000 }),
    });
    expect(buildGoalPlanView(goal('v2-abc'), ctx()).growthLine).toBeNull();
    expect(buildGoalPlanView(goal(null), ctx()).growthLine).toMatch(/^Assuming growth at 10%/);
  });

  it('kids 529 and nest egg carry a growthLine, the baby fund never does', () => {
    const goal = fundGoal([
      { label: 'Ada', birthYear: Y - 3, birthMonth: 3, accounts: { plan529: account(0, { anchorMonthly: 200 }), brokerage: account(0, { anchorMonthly: 300 }) } },
      fundKid('Theo', Y + 5, 6, [Y + 5, 6], 20_800, account(0, { anchorMonthly: 500 })),
    ], { plan529Target: 100_000, brokerageTarget: 250_000 }, {
      planAnchoredAt: '2023-11-15T00:00:00',
      unitTallies: {
        'kid:0:plan529': tally({ tally: 7_200 }),
        'kid:0:brokerage': tally({ tally: 10_800 }),
        'kid:1:firstYear': tally({ tally: 18_000 }),
      },
    });
    const view = buildGoalPlanView(goal, ctx());
    const row = (account: 'plan529' | 'brokerage') => view.kids!.find(a => a.account === account)!.perKid[0];
    expect(row('plan529').growthLine).toBe(`Assuming growth at 10%, worth ~${fmtMoney(futureValue(0, 200, 36, 0.10, []))} today`);
    expect(row('brokerage').growthLine).toBe(`Assuming growth at 10%, worth ~${fmtMoney(futureValue(0, 300, 36, 0.10, []))} today`);
    expect(view.firstYear!.rows[0].growthLine).toBeNull();
  });
});
