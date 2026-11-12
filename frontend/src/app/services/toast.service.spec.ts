import { TestBed } from '@angular/core/testing';
import {
  ERROR_TOAST_DURATION_MS, TOAST_DURATION_MS, ToastItem, ToastService, severityForColor
} from './toast.service';

describe('ToastService', () => {
  let service: ToastService;
  let received: ToastItem[];

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ToastService);
    received = [];
    service.items$.subscribe(item => received.push(item));
  });

  describe('severityForColor', () => {
    it('maps the five Ionic colours onto the five severities', () => {
      expect(severityForColor('success')).toBe('success');
      expect(severityForColor('danger')).toBe('error');
      expect(severityForColor('warning')).toBe('warning');
      expect(severityForColor('primary')).toBe('info');
      expect(severityForColor('medium')).toBe('neutral');
    });

    it('reads anything else as info', () => {
      expect(severityForColor(undefined)).toBe('info');
      expect(severityForColor('tertiary')).toBe('info');
      expect(severityForColor('')).toBe('info');
    });
  });

  describe('showToast', () => {
    it('defaults to an info toast with the standard duration', () => {
      service.showToast('Saved.');

      expect(received).toEqual([{ severity: 'info', message: 'Saved.', title: undefined, duration: TOAST_DURATION_MS }]);
    });

    it('turns a danger colour into an error that stays up longer', () => {
      service.showToast('Nope.', 'danger');

      expect(received[0].severity).toBe('error');
      expect(received[0].duration).toBe(ERROR_TOAST_DURATION_MS);
    });

    it('keeps a custom duration', () => {
      service.showToast('Quick.', 'danger', 2000);
      service.showToast('Slow.', 'success', 5000);

      expect(received.map(i => i.duration)).toEqual([2000, 5000]);
    });

    it('emits one item per call, in order', () => {
      service.showToast('One', 'success');
      service.showToast('Two', 'warning');
      service.showToast('Three', 'medium');

      expect(received.map(i => [i.severity, i.message])).toEqual([
        ['success', 'One'], ['warning', 'Two'], ['neutral', 'Three'],
      ]);
    });
  });

  describe('show', () => {
    it('passes severity, title and duration through', () => {
      service.show({ severity: 'warning', title: 'Heads up', message: 'Check this.', duration: 1500 });

      expect(received).toEqual([{ severity: 'warning', title: 'Heads up', message: 'Check this.', duration: 1500 }]);
    });

    it('fills in the default duration by severity', () => {
      service.show({ severity: 'error', message: 'Broke.' });
      service.show({ severity: 'neutral', message: 'Fine.' });

      expect(received.map(i => i.duration)).toEqual([ERROR_TOAST_DURATION_MS, TOAST_DURATION_MS]);
    });
  });
});
