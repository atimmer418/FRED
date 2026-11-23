import { InjectionToken } from '@angular/core';

/** The goal pages' clock. One value per page, read once; specs provide a fixed date. */
export const GOAL_PAGE_NOW = new InjectionToken<() => Date>('GOAL_PAGE_NOW', {
  providedIn: 'root',
  factory: () => () => new Date(),
});
