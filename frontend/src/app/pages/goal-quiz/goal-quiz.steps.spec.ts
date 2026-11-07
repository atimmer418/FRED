import { GoalForm, defaultForm } from '../goal/goal-form.model';
import { QuizStepId, quizStepsFor } from './goal-quiz.steps';
import { GoalType } from '../../models/goal.model';

const NOW = new Date(2026, 10, 15);

function ids(form: GoalForm): QuizStepId[] {
  return quizStepsFor(form, NOW).map(s => s.id);
}

describe('quizStepsFor', () => {
  it('kids steps are count, when, cost, fund, college, nest by default', () => {
    expect(ids(defaultForm('KIDS', NOW)))
      .toEqual(['kids_count', 'kids_when', 'kids_cost', 'kids_fund', 'kids_college', 'kids_nest']);
  });

  it('college off removes kids_college and nest off removes kids_nest; both off makes kids_fund last', () => {
    const noCollege = defaultForm('KIDS', NOW);
    noCollege.kids.collegeChoice = 'none';
    expect(ids(noCollege)).toEqual(['kids_count', 'kids_when', 'kids_cost', 'kids_fund', 'kids_nest']);

    const noNest = defaultForm('KIDS', NOW);
    noNest.kids.at18Choice = 'none';
    expect(ids(noNest)).toEqual(['kids_count', 'kids_when', 'kids_cost', 'kids_fund', 'kids_college']);

    const neither = defaultForm('KIDS', NOW);
    neither.kids.collegeChoice = 'none';
    neither.kids.at18Choice = 'none';
    const steps = ids(neither);
    expect(steps).toEqual(['kids_count', 'kids_when', 'kids_cost', 'kids_fund']);
    expect(steps[steps.length - 1]).toBe('kids_fund');
  });

  it('house has price and terms; vacation, emergency, wedding and custom have one step each', () => {
    expect(ids(defaultForm('HOUSE', NOW))).toEqual(['house_price', 'house_terms']);
    expect(ids(defaultForm('CAR', NOW))).toEqual(['car_price', 'car_terms']);
    expect(ids(defaultForm('VACATION', NOW))).toEqual(['vacation']);
    expect(ids(defaultForm('EMERGENCY_FUND', NOW))).toEqual(['emergency']);
    expect(ids(defaultForm('WEDDING', NOW))).toEqual(['wedding']);
    expect(ids(defaultForm('CUSTOM', NOW))).toEqual(['custom']);
  });

  it('carries the spec copy verbatim on the kids steps', () => {
    const steps = quizStepsFor(defaultForm('KIDS', NOW), NOW);
    expect(steps.map(s => [s.eyebrow, s.title])).toEqual([
      ['Your family', 'How many kids are you planning for?'],
      ['Timing', "When do you think they'll arrive?"],
      ['The real number', 'What one kid costs'],
      ['Your plan', 'Choose what to fund'],
      ['College', 'Saving for college?'],
      ['A nest egg at 18', 'How much per kid?'],
    ]);
    expect(steps[3].subtitle).toBe('Turn one off to see what it would save you.');
  });

  // The one-line proxy for the 390px copy audit. The two kids titles the spec approved as
  // two-liners are exempt; every other title stays on one line and every subtitle is one line.
  it('every step title is at most 24 characters and every subtitle at most 60', () => {
    const twoLiners = ['How many kids are you planning for?', "When do you think they'll arrive?"];
    const types: GoalType[] = ['KIDS', 'HOUSE', 'CAR', 'VACATION', 'EMERGENCY_FUND', 'WEDDING', 'CUSTOM'];
    for (const type of types) {
      for (const step of quizStepsFor(defaultForm(type, NOW), NOW)) {
        expect(step.title.length).toBeGreaterThan(0);
        expect(step.subtitle.length).toBeGreaterThan(0);
        if (!twoLiners.includes(step.title)) {
          expect(step.title.length).withContext(`${step.id} title`).toBeLessThanOrEqual(24);
        }
        expect(step.subtitle.length).withContext(`${step.id} subtitle`).toBeLessThanOrEqual(60);
        expect(step.title + step.subtitle).not.toContain(String.fromCharCode(0x2014));
      }
    }
  });
});
