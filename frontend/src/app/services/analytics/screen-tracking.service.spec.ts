import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AnalyticsService } from './analytics.service';
import { ScreenTrackingService } from './screen-tracking.service';

@Component({ standalone: true, template: 'page' })
class StubPage {}

/**
 * Exercises the real router rather than faking NavigationEnd, because the thing worth testing is
 * that the name comes from the route CONFIG: a spec that fed in its own event would pass even if
 * the service read the URL and leaked the id.
 */
describe('ScreenTrackingService', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let router: Router;
  let service: ScreenTrackingService;

  beforeEach(() => {
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['screen']);

    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: '', component: StubPage },
          { path: 'my-profile', component: StubPage },
          { path: 'beneficiaries', component: StubPage },
          { path: 'beneficiaries/edit/:id', component: StubPage },
          { path: 'tabs/:tab', component: StubPage },
        ]),
        ScreenTrackingService,
        { provide: AnalyticsService, useValue: analytics },
      ],
    });

    router = TestBed.inject(Router);
    service = TestBed.inject(ScreenTrackingService);
  });

  it('reports a screen view for a pushed page, without a per-page call', async () => {
    service.start();

    await router.navigateByUrl('/my-profile');

    expect(analytics.screen).toHaveBeenCalledWith('/my-profile');
  });

  it('reports the parameterised route, never the id in the URL', async () => {
    service.start();

    await router.navigateByUrl('/beneficiaries/edit/8f2c1b44-0d6e-4a19-9c3a-7e51d0b6a912');

    expect(analytics.screen).toHaveBeenCalledWith('/beneficiaries/edit/:id');
    const names = analytics.screen.calls.allArgs().map(([name]) => name);
    expect(names.join(' ')).not.toContain('8f2c1b44');
  });

  it('leaves the tabs to the pager, which knows which tab is actually showing', async () => {
    service.start();

    await router.navigateByUrl('/tabs/tab3');

    expect(analytics.screen).not.toHaveBeenCalled();
  });

  it('reports the root as /', async () => {
    service.start();

    await router.navigateByUrl('/');

    expect(analytics.screen).toHaveBeenCalledWith('/');
  });

  it('does not subscribe twice when started again', async () => {
    service.start();
    service.start();

    await router.navigateByUrl('/my-profile');

    expect(analytics.screen).toHaveBeenCalledTimes(1);
  });

  it('reports nothing before it is started', async () => {
    await router.navigateByUrl('/my-profile');

    expect(analytics.screen).not.toHaveBeenCalled();
  });
});
