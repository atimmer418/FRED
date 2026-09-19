import { ACTIVE_MOCK_SUBSCRIPTION_TIER, tierMockProfilePatch } from './tier-mock';

describe('tier-mock (dev)', () => {
  it('is forced off under karma regardless of the toggle', () => {
    expect(ACTIVE_MOCK_SUBSCRIPTION_TIER).toBeNull();
  });

  (['core', 'plus', 'pro'] as const).forEach((tier) => {
    it(`${tier} patches the tier with a monthly billing period`, () => {
      expect(tierMockProfilePatch(tier)).toEqual({ selectedTier: tier, billingPeriod: 'monthly' });
    });
  });

  it('EXPIRED sends an explicit null so the server clears the tier', () => {
    const patch = tierMockProfilePatch('EXPIRED');
    expect(patch.selectedTier).toBeNull();
    expect('selectedTier' in patch).toBeTrue();
    expect('billingPeriod' in patch).toBeFalse();
  });
});
