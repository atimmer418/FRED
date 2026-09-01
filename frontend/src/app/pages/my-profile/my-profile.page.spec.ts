/**
 * my-profile.page.spec.ts
 *
 * Direct-construction unit tests (no TestBed): the page injects 9+ services,
 * and only the freedom-timeline derivation is under test here. The previous
 * CLI-generated stub had no providers and could not run.
 *
 * Freedom year source: FreedomStatsService.stats() — the same live signal
 * tab3 renders (freedom date unification, 2026-07-07 spec).
 */
import { of } from 'rxjs';
import { MyProfilePage } from './my-profile.page';
import { FirstTimeTourService } from '../../services/first-time-tour.service';

function makePage(freedomYearStat: string): MyProfilePage {
  const accountStatusMock = { actionRequired$: of(false) } as any;
  const freedomStatsMock = {
    stats: () => ({ freedomYear: freedomYearStat, freedomAge: '—', dollarsAway: '—' })
  } as any;

  return new MyProfilePage(
    {} as any,            // AuthService
    {} as any,            // ToastService
    {} as any,            // PortfolioService
    accountStatusMock,    // AccountStatusService
    {} as any,            // MonthlyFreedomUpdateService
    {} as any,            // Router
    {} as any,            // AlertController
    {} as any,            // NavController
    {} as any,            // ModalController
    {} as any,            // GoalsService (FRED-216)
    freedomStatsMock      // FreedomStatsService
  );
}

describe('MyProfilePage — freedom timeline', () => {

  it('derives the numeric freedom year from the live FreedomStatsService signal', () => {
    const page = makePage('2058');
    expect(page.freedomYear).toBe(2058);
  });

  it('computes yearsToFreedom from the live year', () => {
    const page = makePage('2058');
    expect(page.yearsToFreedom).toBe(2058 - new Date().getFullYear());
  });

  it('returns null (placeholder branch) while stats show the loading dash', () => {
    const page = makePage('—');
    expect(page.freedomYear).toBeNull();
    expect(page.yearsToFreedom).toBeNull();
  });
});
