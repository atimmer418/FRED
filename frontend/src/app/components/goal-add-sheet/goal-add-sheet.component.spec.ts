/**
 * goal-add-sheet.component.spec.ts
 *
 * The sheet's only analytics duty is the inline field errors: a rejected attempt is an error the
 * user was shown, and it is the one place on this surface where a message exists that must never
 * be sent (every validation string quotes the user's own goal fields).
 *
 * `goal_changed` is deliberately NOT fired here — the sheet reports a change as a dismiss role and
 * only the presenter knows the resulting goal count. Its specs live in my-profile.analytics.spec.ts.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModalController } from '@ionic/angular/standalone';
import { GoalAddSheetComponent } from './goal-add-sheet.component';
import { Goal } from '../../services/goals.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { AnalyticsAdapter } from '../../services/analytics/analytics-adapter';

const CURRENT_YEAR = new Date().getFullYear();

const EXISTING_GOAL: Goal = {
  id: 'g-1',
  name: 'House down payment',
  icon: 'home',
  targetAmount: 60_000,
  targetYear: CURRENT_YEAR + 5,
  startingValue: 5_000,
  perPaycheckOutsideFRED: 275,
  completed: false,
};

describe('GoalAddSheetComponent — inline validation errors', () => {
  let fixture: ComponentFixture<GoalAddSheetComponent>;
  let dismissSpy: jasmine.Spy;
  let trackSpy: jasmine.Spy;

  function setup(editGoal: Goal | null = null): void {
    dismissSpy = jasmine.createSpy('dismiss');
    // The real service with a spied track(), so trackErrorShown's own mapping is under test and
    // the assertion below is the exact event object the service is handed.
    const analytics = new AnalyticsService({} as unknown as AnalyticsAdapter);
    trackSpy = spyOn(analytics, 'track');
    TestBed.configureTestingModule({
      imports: [GoalAddSheetComponent],
      providers: [
        { provide: ModalController, useValue: { dismiss: dismissSpy } },
        { provide: AnalyticsService, useValue: analytics },
      ],
    });
    fixture = TestBed.createComponent(GoalAddSheetComponent);
    fixture.componentInstance.editGoal = editGoal;
    fixture.componentInstance.payFrequency = 'biweekly';
    fixture.detectChanges();
  }

  /** The sheet's real CTA. */
  function tapSave(): void {
    (fixture.nativeElement.querySelector('.gs-cta') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  function fillValidGoal(): void {
    const sheet = fixture.componentInstance;
    sheet.goalName = 'Dream vacation';
    sheet.selectedIcon = 'flag';
    sheet.targetAmount = 8_000;
    sheet.targetYear = CURRENT_YEAR + 2;
    sheet.startingValue = 500;
    sheet.perPaycheckOutsideFRED = 100;
    fixture.detectChanges();
  }

  it('fires error_shown once for a rejected empty form, carrying no field text', () => {
    setup();

    tapSave();

    expect(trackSpy).toHaveBeenCalledTimes(1);
    expect(trackSpy).toHaveBeenCalledWith({
      name: 'error_shown',
      props: {
        category: 'validation',
        surface: 'goals',
        display: 'inline_field',
        http_status: null,
        retry_available: false,
      },
    });
    expect(dismissSpy).not.toHaveBeenCalled();
  });

  it('sends one event even though several fields fail at once', () => {
    setup();

    tapSave();

    // Name, amount and year all render their own message; the user tapped once.
    expect(fixture.nativeElement.querySelectorAll('.gs-error').length).toBeGreaterThan(1);
    expect(trackSpy).toHaveBeenCalledTimes(1);
  });

  it('sends nothing when the form is valid and the goal is handed back', () => {
    setup();
    fillValidGoal();

    tapSave();

    expect(trackSpy).not.toHaveBeenCalled();
    expect(dismissSpy).toHaveBeenCalledWith(
      jasmine.objectContaining({ icon: 'flag', targetAmount: 8_000 }),
      'saved'
    );
  });

  it('reports a rejected complete-toggle attempt the same way', () => {
    setup(EXISTING_GOAL);
    fixture.componentInstance.targetAmount = 0;          // invalidated mid-edit
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.gs-complete') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(trackSpy).toHaveBeenCalledTimes(1);
    expect(trackSpy.calls.mostRecent().args[0].props.display).toBe('inline_field');
    expect(dismissSpy).not.toHaveBeenCalled();
  });

  it('sends nothing for the paths that cannot fail: remove and cancel', () => {
    setup(EXISTING_GOAL);

    (fixture.nativeElement.querySelector('.gs-remove') as HTMLButtonElement).click();
    (fixture.nativeElement.querySelector('.gs-cancel') as HTMLButtonElement).click();

    expect(trackSpy).not.toHaveBeenCalled();
  });
});
