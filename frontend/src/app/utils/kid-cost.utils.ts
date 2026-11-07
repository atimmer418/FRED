import {
  CHILDBIRTH_OUT_OF_POCKET,
  COLLEGE_ONE_YEAR,
  CPI_U_2015_AVERAGE,
  CPI_U_LATEST,
  CPI_U_LATEST_LABEL,
  FRED_ESTIMATE_FAMILY_SIZE_FACTORS,
  KID_COST_SOURCES,
  USDA_2015_ANNUAL_BY_BAND,
  USDA_2015_TOTAL,
  USDA_FAMILY_SIZE_FACTORS,
} from '../constants/kid-costs';

// Pure helpers over the kid cost constants. Derived values here are FRED's own arithmetic.
// Dollar values are kept unrounded; the view rounds once, for display.

export type KidCostStageKey = '0-2' | '3-5' | '6-11' | '12-17';

export interface KidCostStage {
  key: KidCostStageKey;
  label: string;
  years: number;
  annual: number;
  monthly: number;
  total: number;
}

export type FamilySizeWording = 'only' | 'two' | 'three_plus' | 'four' | 'five';

export interface KidCostFigures {
  kidsCount: number;
  sizeFactor: number;
  sizeWording: FamilySizeWording;
  perChildTotal: number;
  familyTotal: number;
  stages: KidCostStage[];
  childbirthOutOfPocket: number;
  asOfLabel: string;
}

export interface CollegePreset {
  key: 'public_tuition' | 'public_full' | 'private_tuition';
  label: string;
  amount: number;
}

/** Brings 2015 dollars to the latest CPI-U month. */
export function inflationFactor(): number {
  return CPI_U_LATEST / CPI_U_2015_AVERAGE;
}

/**
 * Per-kid family-size factor: USDA Table 1 for 1 to 3 kids (1.27 / 1.00 / 0.76), the FRED estimate
 * for 4 and 5 (0.64 / 0.568, see kid-costs.ts). Five is the cap, so anything higher reads as five.
 */
export function familySizeFactor(kidsCount: number): number {
  if (kidsCount <= 2) return firstYearFactor(kidsCount);
  if (kidsCount === 3) return USDA_FAMILY_SIZE_FACTORS.threeOrMore;
  if (kidsCount === 4) return FRED_ESTIMATE_FAMILY_SIZE_FACTORS.four;
  return FRED_ESTIMATE_FAMILY_SIZE_FACTORS.five;
}

/**
 * The factor for a first-year fund at this birth rank: 1.27, 1.00, then 0.76 for every later
 * rank (USDA only). The 4 and 5 kid estimates never apply to a fund.
 */
export function firstYearFactor(rank: number): number {
  if (rank <= 1) return USDA_FAMILY_SIZE_FACTORS.one;
  if (rank === 2) return USDA_FAMILY_SIZE_FACTORS.two;
  return USDA_FAMILY_SIZE_FACTORS.threeOrMore;
}

function sizeWordingFor(kidsCount: number): FamilySizeWording {
  if (kidsCount <= 1) return 'only';
  if (kidsCount === 2) return 'two';
  if (kidsCount === 3) return 'three_plus';
  if (kidsCount === 4) return 'four';
  return 'five';
}

const MONTHS_TO_18 = 216;

function perKidTotal(kidsCount: number): number {
  return USDA_2015_TOTAL * inflationFactor() * familySizeFactor(kidsCount);
}

/** What one kid costs a month, birth to 18, in a family of this size: per-kid total / 216, nearest $10. */
export function perKidMonthly(kidsCount: number): number {
  return Math.round(perKidTotal(kidsCount) / MONTHS_TO_18 / 10) * 10;
}

/** What the whole family's kids cost a month: per-kid total x count / 216, nearest $10. */
export function familyMonthly(kidsCount: number): number {
  const count = Math.max(1, kidsCount);
  return Math.round((perKidTotal(count) * count) / MONTHS_TO_18 / 10) * 10;
}

const STAGE_DEFS: ReadonlyArray<{ key: KidCostStageKey; label: string; fromAges: ReadonlyArray<number> }> = [
  { key: '0-2', label: 'Baby and toddler, 0 to 2', fromAges: [0] },
  { key: '3-5', label: 'Preschool, 3 to 5', fromAges: [3] },
  { key: '6-11', label: 'Grade school, 6 to 11', fromAges: [6, 9] },
  { key: '12-17', label: 'Teen years, 12 to 17', fromAges: [12, 15] },
];

export function kidCostFigures(kidsCount: number): KidCostFigures {
  const inflation = inflationFactor();
  const sizeFactor = familySizeFactor(kidsCount);
  const perChildUnrounded = USDA_2015_TOTAL * inflation * sizeFactor;

  const stages: KidCostStage[] = STAGE_DEFS.map(def => {
    const bands = USDA_2015_ANNUAL_BY_BAND.filter(b => def.fromAges.includes(b.fromAge));
    const years = bands.length * 3;
    const total = bands.reduce((sum, b) => sum + 3 * b.annual * inflation * sizeFactor, 0);
    const annual = total / years;
    return { key: def.key, label: def.label, years, annual, monthly: annual / 12, total };
  });

  return {
    kidsCount,
    sizeFactor,
    sizeWording: sizeWordingFor(kidsCount),
    perChildTotal: Math.round(perChildUnrounded),
    familyTotal: Math.round(perChildUnrounded * kidsCount),
    stages,
    childbirthOutOfPocket: CHILDBIRTH_OUT_OF_POCKET,
    asOfLabel: CPI_U_LATEST_LABEL,
  };
}

/**
 * First-year fund target for the kid at this birth rank: the 0-2 annual figure (inflated, for
 * the family-size factor of that rank) plus the birth figure, rounded UP to the next $100.
 */
export function firstYearTargetForRank(rank: number): number {
  const zeroToTwo = USDA_2015_ANNUAL_BY_BAND[0].annual;
  const sum = zeroToTwo * inflationFactor() * firstYearFactor(rank) + CHILDBIRTH_OUT_OF_POCKET;
  const cents = Math.round(sum * 100) / 100; // guard the ceil against floating-point noise
  return Math.ceil(cents / 100) * 100;
}

/**
 * Birth rank per input index, starting at 1. Ordered by (birthYear, birthMonth ?? 7) ascending;
 * kids with no birth year go last; ties keep list order.
 */
export function kidRanks(kids: ReadonlyArray<{ birthYear: number | null; birthMonth: number | null }>): number[] {
  const order = kids
    .map((k, index) => ({ k, index }))
    .sort((a, b) => {
      const aUndecided = a.k.birthYear === null;
      const bUndecided = b.k.birthYear === null;
      if (aUndecided || bUndecided) {
        if (aUndecided && bUndecided) return a.index - b.index;
        return aUndecided ? 1 : -1;
      }
      const aKey = (a.k.birthYear as number) * 12 + ((a.k.birthMonth ?? 7) - 1);
      const bKey = (b.k.birthYear as number) * 12 + ((b.k.birthMonth ?? 7) - 1);
      return aKey - bKey || a.index - b.index;
    });
  const ranks: number[] = new Array(kids.length);
  order.forEach((entry, position) => {
    ranks[entry.index] = position + 1;
  });
  return ranks;
}

/** Four years at today's College Board prices. */
export function collegePresets(): CollegePreset[] {
  return [
    { key: 'public_tuition', label: "In-state public tuition, at today's prices", amount: 4 * COLLEGE_ONE_YEAR.publicTuition },
    { key: 'public_full', label: "In-state public full cost, at today's prices", amount: 4 * COLLEGE_ONE_YEAR.publicFullCost },
    { key: 'private_tuition', label: "Private college tuition, at today's prices", amount: 4 * COLLEGE_ONE_YEAR.privateTuition },
  ];
}

/** The sources shown on the cost card (the College Board is credited on the college question). */
export function costCardSources() {
  return KID_COST_SOURCES.filter(s => s.usedIn === 'costs');
}
