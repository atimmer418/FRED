import { TestBed } from '@angular/core/testing';

import { GoalDraftService } from './goal-draft.service';
import { defaultForm } from '../pages/goal/goal-form.model';
import { Goal } from '../models/goal.model';

const NOW = new Date(2026, 10, 15);

function houseGoal(): Goal {
  return {
    id: 7, clientId: null, type: 'HOUSE', name: 'First home', icon: 'home',
    config: { price: 400_000, downPaymentPct: 20, targetYear: 2031 },
    currentBalance: 0, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0,
    planRate: 0.1, planRateKind: 'market', planAnchoredAt: '2026-01-01',
    completed: false, sortOrder: 0, createdAt: '', updatedAt: '',
  };
}

describe('GoalDraftService', () => {
  let service: GoalDraftService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(GoalDraftService);
  });

  it('set stores the form and existing goal; clear empties it', () => {
    expect(service.draft()).toBeNull();

    const form = defaultForm('HOUSE', NOW);
    service.set(form, null);
    expect(service.draft()).toEqual({ form, existing: null });

    const goal = houseGoal();
    service.set(form, goal);
    expect(service.draft()?.existing).toBe(goal);

    service.clear();
    expect(service.draft()).toBeNull();
  });
});
