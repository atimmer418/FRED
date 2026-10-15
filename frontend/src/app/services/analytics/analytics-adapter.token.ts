import { InjectionToken } from '@angular/core';
import { AnalyticsAdapter } from './analytics-adapter';
import { LazyPostHogAdapter } from './lazy-posthog.adapter';

/**
 * The vendor behind `AnalyticsService`. A token rather than a direct dependency for two reasons:
 * an interface has no runtime representation to inject, and specs can swap in a recording double
 * without ever loading a vendor SDK.
 *
 * The default is the LAZY adapter, so posthog-js ships as its own chunk and is fetched only if
 * analytics actually starts. A static import cost 340 kB of initial bundle for code that does not
 * run while the user is opted out.
 *
 * Swapping vendors means writing one adapter and changing this factory.
 */
export const ANALYTICS_ADAPTER = new InjectionToken<AnalyticsAdapter>('AnalyticsAdapter', {
  providedIn: 'root',
  factory: () => new LazyPostHogAdapter(),
});
