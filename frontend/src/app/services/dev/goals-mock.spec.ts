import { ACTIVE_GOALS_MOCK, buildMockGoals, mockGoals } from './goals-mock';
import { GoalType } from '../../models/goal.model';

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

  it('mockGoals returns an empty list while the toggle is off', () => {
    expect(mockGoals(now)).toEqual([]);
  });
});
