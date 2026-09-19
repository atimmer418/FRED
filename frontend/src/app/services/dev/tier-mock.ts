/**
 * DEV-ONLY subscription tier mock (write-through).
 *
 * The subscription tier ("Piggy" = core, Piggy Plus = plus, Piggy Pro = pro) is a plain
 * string on the user row, so eyeballing the three tiers normally means editing the DB or
 * PUTting /user/profile and reloading. This module lets you pick a tier on the local build
 * instead, exactly like MOCK_CHOICES in portfolio-mock.ts.
 *
 * Usage: uncomment ONE entry in MOCK_CHOICES below. Leave them all commented for the real
 * server value. AuthService only consults this when environment.local is true (the local dev
 * loop) — the deployed dev/test/prod builds all have local:false, so it can never leak there.
 * Karma additionally forces it null so specs that assert on the exact HTTP traffic of
 * getUserProgress() are immune to the toggle state.
 *
 * How it takes effect: it is a REAL WRITE, not a read overlay. Once per app launch, before
 * the first GET /user/progress, AuthService PUTs the chosen tier to /user/profile so the
 * database row itself changes. Every read after that is the genuine server value, so the
 * backend gates (e.g. TLH requiring "pro") agree with what the UI shows, and tapping an
 * in-app upgrade renders normally. Reloading the app re-applies the chosen tier.
 */

/** 'EXPIRED' = no active subscription (selectedTier absent), the state my-profile calls isSubExpired. */
export type MockSubscriptionTier = 'core' | 'plus' | 'pro' | 'EXPIRED';

/**
 * DEV TOGGLE — uncomment ONE line to force that tier; all commented = real server value.
 * The first uncommented entry wins.
 */
const MOCK_CHOICES: MockSubscriptionTier[] = [
  // 'core',     // Piggy — goals veil, default quarterly rebalance, TLH locked
  'plus',     // Piggy Plus — multi-goal tracking, custom rebalance
  // 'pro',      // Piggy Pro — everything, TLH unlocked (backend checks the literal "pro")
  // 'EXPIRED',  // fully onboarded but no active subscription
];

/** Forced null under karma regardless of the toggle (see portfolio-mock.ts for the rationale). */
const IN_KARMA = typeof window !== 'undefined' && !!(window as any).__karma__;

export const ACTIVE_MOCK_SUBSCRIPTION_TIER: MockSubscriptionTier | null =
  IN_KARMA ? null : (MOCK_CHOICES.find(Boolean) ?? null);

/** Body PUT to /user/profile. `selectedTier: null` is honoured by the backend as "clear the tier". */
export interface TierMockProfilePatch {
  selectedTier: 'core' | 'plus' | 'pro' | null;
  billingPeriod?: 'monthly';
}

/**
 * Pure: the /user/profile patch that makes the server row match `tier`. 'EXPIRED' sends an
 * explicit null so `selectedTier != null` checks (tabs.page, my-profile) see no subscription.
 */
export function tierMockProfilePatch(tier: MockSubscriptionTier): TierMockProfilePatch {
  return tier === 'EXPIRED' ? { selectedTier: null } : { selectedTier: tier, billingPeriod: 'monthly' };
}
