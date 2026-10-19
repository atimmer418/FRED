import { of } from 'rxjs';
import { NavController } from '@ionic/angular';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { GetStartedComponent } from './get-started.component';

/**
 * The component is built by hand rather than through TestBed: the only thing under test is which
 * analytics call the handler makes, and a direct construction keeps the swiper, the preloads and
 * the page animation out of it.
 */
describe('GetStartedComponent — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let navController: jasmine.SpyObj<NavController>;
  let authService: jasmine.SpyObj<AuthService>;
  let component: GetStartedComponent;

  beforeEach(() => {
    localStorage.removeItem('onboardingStep');

    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    navController = jasmine.createSpyObj<NavController>('NavController', ['navigateRoot']);
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['completeStep']);
    authService.completeStep.and.returnValue(of({}));

    component = new GetStartedComponent(navController, authService, analytics);
  });

  afterEach(() => {
    localStorage.removeItem('onboardingStep');
  });

  it('reports the step as viewed and not resumed on a first arrival', () => {
    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('get_started', null, false);
  });

  it('reports resumed when a stored onboardingStep shows this device has been here before', () => {
    localStorage.setItem('onboardingStep', '3');

    component.ngOnInit();

    expect(analytics.trackOnboardingStepViewed).toHaveBeenCalledWith('get_started', null, true);
  });

  it('reports the step completed at the tap, not in the progress callback', () => {
    component.getSetUp();

    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledWith('get_started', null);
    // Fired before the fire-and-forget progress write, which navigates either way.
    expect(analytics.trackOnboardingStepCompleted).toHaveBeenCalledBefore(authService.completeStep);
  });

  it('sends no other event from this screen', () => {
    component.ngOnInit();
    component.getSetUp();

    expect(analytics.track).not.toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  });
});
