import { of } from 'rxjs';
import { RetirementPlanningComponent } from './retirement-planning.component';
import { RetirementSectionService } from '../../services/retirement-section.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';

/**
 * FRED-217: an outside caller (the MFU Retire card) can deep-link the retirement screen to its
 * Education section through RetirementSectionService. The component subscribes on init and switches
 * sections when a request arrives.
 */
describe('RetirementPlanningComponent — section deep-link', () => {
  function build() {
    const section = new RetirementSectionService();
    const authService = { userProgress$: of(null), getCurrentUserId: () => null } as any;
    const historyService = { getHistory: () => [] } as any;
    const analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track']);
    const cmp = new RetirementPlanningComponent(
      authService, historyService, {} as any, {} as any, section, analytics);
    return { cmp, section };
  }

  it('starts on the simulator section', () => {
    const { cmp } = build();
    expect(cmp.selectedSection).toBe('simulator');
  });

  it('switches to the Education section when a request is emitted after init', () => {
    const { cmp, section } = build();
    cmp.ngOnInit();

    section.requestSection('education');

    expect(cmp.selectedSection).toBe('education');
  });
});
