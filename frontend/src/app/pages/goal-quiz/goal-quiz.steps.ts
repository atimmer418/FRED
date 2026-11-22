// The quiz's step table: which screens run for a form, with each screen's copy. Pure, no Angular.
// Copy is the spec's (docs/superpowers/specs/2026-11-06-goals-quiz-and-plan-page-redesign.md),
// shortened where a subtitle measured wider than the 338px a 390px screen gives it.

import { GoalForm } from '../goal/goal-form.model';

export type QuizStepId =
  | 'kids_count' | 'kids_when' | 'kids_cost' | 'kids_fund' | 'kids_college' | 'kids_nest'
  | 'house_price' | 'house_terms'
  | 'car_price' | 'car_terms'
  | 'vacation' | 'emergency' | 'wedding' | 'custom';

export interface QuizStep {
  id: QuizStepId;
  eyebrow: string;
  title: string;
  subtitle: string;
}

const STEPS: Record<QuizStepId, Omit<QuizStep, 'id'>> = {
  kids_count: {
    eyebrow: 'Your family',
    title: 'How many kids are you planning for?',
    subtitle: 'Count the ones already here too.',
  },
  kids_when: {
    eyebrow: 'Timing',
    title: "When do you think they'll arrive?",
    subtitle: 'A rough guess is fine.',
  },
  kids_cost: {
    eyebrow: 'The real number',
    title: 'What one kid costs',
    subtitle: "The U.S. average, adjusted to today's prices.",
  },
  kids_fund: {
    eyebrow: 'Your plan',
    title: 'Choose what to fund',
    subtitle: 'Turn one off to see what it would save you.',
  },
  kids_college: {
    eyebrow: 'College',
    title: 'Saving for college?',
    subtitle: "Four years of tuition per kid, at today's prices.",
  },
  kids_nest: {
    eyebrow: 'A nest egg at 18',
    title: 'How much per kid?',
    subtitle: 'You invest it as they grow. Theirs at 18.',
  },
  house_price: {
    eyebrow: 'Your home',
    title: 'What will it cost?',
    subtitle: 'A rough price for the home you have in mind.',
  },
  house_terms: {
    eyebrow: 'Your home',
    title: 'How much, and when?',
    subtitle: 'FRED plans the down payment. The mortgage comes after.',
  },
  car_price: {
    eyebrow: 'Your car',
    title: 'What will it cost?',
    subtitle: 'New or used, the price you expect to pay.',
  },
  car_terms: {
    eyebrow: 'Your car',
    title: 'How much, and when?',
    subtitle: 'FRED plans the down payment. The loan comes after.',
  },
  vacation: {
    eyebrow: 'Vacation',
    title: 'Trips and budget',
    subtitle: 'Kept in cash, ready to spend.',
  },
  emergency: {
    eyebrow: 'Emergency fund',
    title: 'Your safety net',
    subtitle: 'Three to six months of expenses, in cash.',
  },
  wedding: {
    eyebrow: 'Wedding',
    title: 'The budget, and when',
    subtitle: "What you'd like to have set aside by the day.",
  },
  custom: {
    eyebrow: 'Something else',
    title: 'How much, and when?',
    subtitle: 'Any target with a date.',
  },
};

function step(id: QuizStepId): QuizStep {
  return { id, ...STEPS[id] };
}

/**
 * The steps that will actually run for this form, in order. Kids: college only while a college
 * target is chosen, the nest egg only while an at-18 target is chosen; the fund step is always
 * present (its Baby fund row hides itself when no kid is unborn). `now` is part of the contract so
 * a step that depends on the calendar can be added without changing callers.
 */
export function quizStepsFor(form: GoalForm, _now: Date): QuizStep[] {
  switch (form.type) {
    case 'KIDS': {
      const ids: QuizStepId[] = ['kids_count', 'kids_when', 'kids_cost', 'kids_fund'];
      if (form.kids.collegeChoice !== 'none') ids.push('kids_college');
      if (form.kids.at18Choice !== 'none') ids.push('kids_nest');
      return ids.map(step);
    }
    case 'HOUSE': return [step('house_price'), step('house_terms')];
    case 'CAR': return [step('car_price'), step('car_terms')];
    case 'VACATION': return [step('vacation')];
    case 'EMERGENCY_FUND': return [step('emergency')];
    case 'WEDDING': return [step('wedding')];
    default: return [step('custom')];
  }
}
