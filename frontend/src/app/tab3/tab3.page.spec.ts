/**
 * tab3.page.spec.ts — FRED-200
 *
 * Tests that tab3 renders correctly from the FreedomStatsService signals.
 * The old loadStatStrip() tests (FRED-196) have been migrated to
 * freedom-stats.service.spec.ts, which tests the service in isolation.
 */

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { of, BehaviorSubject } from 'rxjs';
import { signal } from '@angular/core';
import { Tab3Page } from './tab3.page';
import { ModalController } from '@ionic/angular/standalone';
import { SettingsService } from '../services/settings.service';
import { PlaidService } from '../services/plaid.service';
import { AuthService } from '../services/auth.service';
import { ToastService } from '../services/toast.service';
import { MfuStoreService } from '../services/mfu-store.service';
import { MfuPresenterService } from '../services/mfu-presenter.service';
import { TabActivationService } from '../services/tab-activation.service';
import { AppLockService } from '../services/app-lock.service';
import { AccountStatusService } from '../services/account-status.service';
import { FreedomStatsService } from '../services/freedom-stats.service';

// ---------------------------------------------------------------------------
// Mock helpers
// ---------------------------------------------------------------------------

function makeSettingsServiceMock() {
  return {
    getPreferences: jasmine.createSpy('getPreferences').and.returnValue(
      new BehaviorSubject({ theme: 'light', notifications: {} }).asObservable()
    ),
    getRecurringInvestment: jasmine.createSpy('getRecurringInvestment').and.returnValue(
      new BehaviorSubject(null).asObservable()
    ),
    updateRecurringInvestment: jasmine.createSpy('updateRecurringInvestment'),
    getNextInvestmentDate: jasmine.createSpy('getNextInvestmentDate').and.returnValue('2026-07-01'),
    applyTheme: jasmine.createSpy('applyTheme')
  };
}

function makePlaidServiceMock() {
  return {
    refreshBankAccountData: jasmine.createSpy('refreshBankAccountData'),
    getCurrentAccount: jasmine.createSpy('getCurrentAccount').and.returnValue(of(null)),
    formatLinkDate: jasmine.createSpy('formatLinkDate').and.returnValue(''),
    getStatusText: jasmine.createSpy('getStatusText').and.returnValue('')
  };
}

function makeAuthServiceMock() {
  return {
    getUserProgress: jasmine.createSpy('getUserProgress').and.returnValue(of({})),
    userProgress$: new BehaviorSubject(null).asObservable(),
    isLoggedIn$: new BehaviorSubject(true).asObservable(),
    reAuthInProgress$: new BehaviorSubject(false).asObservable()
  };
}

function makeToastServiceMock() {
  return { showToast: jasmine.createSpy('showToast') };
}

function makeMfuStoreMock() {
  return { ensureAutoSession: () => of({ shouldShow: false, hasMfuHistory: false, data: null }) };
}
function makeMfuPresenterMock() {
  return { presentAutoIfDue: () => Promise.resolve(), presentReopen: () => Promise.resolve() };
}

function makeAppLockServiceMock() {
  return {
    isCurrentlyLocked: jasmine.createSpy('isCurrentlyLocked').and.returnValue(false),
    isLocked$: new BehaviorSubject(false).asObservable()
  };
}

function makeAccountStatusServiceMock() {
  return {
    actionRequired$: new BehaviorSubject(false).asObservable()
  };
}

function makeModalControllerMock() {
  return {
    create: jasmine.createSpy('create').and.returnValue(
      Promise.resolve({
        present: () => Promise.resolve(),
        onDidDismiss: () => Promise.resolve({ data: null })
      })
    )
  };
}

/** Create a FreedomStatsService mock with configurable signal values. */
function makeFreedomStatsServiceMock(
  isLoading: boolean,
  stats: { freedomYear: string; freedomAge: string; dollarsAway: string },
  equityValue: number = 0
) {
  const equitySignal = signal(equityValue);
  const isLoadingSignal = signal(isLoading);
  const statsSignal = signal(stats);

  return {
    isLoading: isLoadingSignal,
    stats: statsSignal,
    equity: equitySignal,
    refresh: jasmine.createSpy('refresh')
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('Tab3Page — FRED-200 signal-driven stat strip', () => {

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('shows a skeleton bar in each stat slot while isLoading is true, then the numbers once it flips', fakeAsync(() => {
    const freedomStatsMock = makeFreedomStatsServiceMock(
      true,
      { freedomYear: '2045', freedomAge: '42', dollarsAway: '$1.2M' }
    );

    TestBed.configureTestingModule({
      imports: [Tab3Page, RouterTestingModule],
      providers: [
        { provide: ModalController, useValue: makeModalControllerMock() }
      ]
    })
      .overrideProvider(SettingsService, { useValue: makeSettingsServiceMock() })
      .overrideProvider(PlaidService, { useValue: makePlaidServiceMock() })
      .overrideProvider(AuthService, { useValue: makeAuthServiceMock() })
      .overrideProvider(ToastService, { useValue: makeToastServiceMock() })
      .overrideProvider(MfuStoreService, { useValue: makeMfuStoreMock() })
      .overrideProvider(MfuPresenterService, { useValue: makeMfuPresenterMock() })
      .overrideProvider(AppLockService, { useValue: makeAppLockServiceMock() })
      .overrideProvider(AccountStatusService, { useValue: makeAccountStatusServiceMock() })
      .overrideProvider(FreedomStatsService, { useValue: freedomStatsMock });

    const fixture: ComponentFixture<Tab3Page> = TestBed.createComponent(Tab3Page);
    fixture.detectChanges();
    tick(0);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const strip = compiled.querySelector('.stat-strip')!;
    const values = Array.from(compiled.querySelectorAll('.stat-value'));
    expect(strip.getAttribute('aria-busy')).toBe('true');
    expect(values.length).toBe(3);
    // One bar per slot, sized to the number it stands in for, and no number text yet.
    values.forEach(v => {
      expect(v.querySelectorAll('app-skeleton').length).toBe(1);
      expect(v.querySelectorAll('.sk-block').length).toBe(1);
      expect(v.textContent?.trim()).toBe('');
    });
    const bars = values.map(v => v.querySelector('app-skeleton') as HTMLElement);
    expect(bars.map(b => b.style.width)).toEqual(['52px', '36px', '60px']);
    bars.forEach(b => expect((b.querySelector('.sk-block') as HTMLElement).style.height).toBe('18px'));
    expect(compiled.textContent).not.toContain('2045');

    // The data lands: the bars give way to the numbers on the same instance.
    freedomStatsMock.isLoading.set(false);
    fixture.detectChanges();
    expect(strip.getAttribute('aria-busy')).toBeNull();
    expect(compiled.querySelector('app-skeleton')).toBeNull();
    expect(values.map(v => v.textContent?.trim())).toEqual(['2045', '42', '$1.2M']);
  }));

  it('shows real stats once isLoading becomes false', fakeAsync(() => {
    const freedomStatsMock = makeFreedomStatsServiceMock(
      false,
      { freedomYear: '2045', freedomAge: '42', dollarsAway: '$1.2M' },
      50000
    );

    TestBed.configureTestingModule({
      imports: [Tab3Page, RouterTestingModule],
      providers: [
        { provide: ModalController, useValue: makeModalControllerMock() }
      ]
    })
      .overrideProvider(SettingsService, { useValue: makeSettingsServiceMock() })
      .overrideProvider(PlaidService, { useValue: makePlaidServiceMock() })
      .overrideProvider(AuthService, { useValue: makeAuthServiceMock() })
      .overrideProvider(ToastService, { useValue: makeToastServiceMock() })
      .overrideProvider(MfuStoreService, { useValue: makeMfuStoreMock() })
      .overrideProvider(MfuPresenterService, { useValue: makeMfuPresenterMock() })
      .overrideProvider(AppLockService, { useValue: makeAppLockServiceMock() })
      .overrideProvider(AccountStatusService, { useValue: makeAccountStatusServiceMock() })
      .overrideProvider(FreedomStatsService, { useValue: freedomStatsMock });

    const fixture: ComponentFixture<Tab3Page> = TestBed.createComponent(Tab3Page);
    fixture.detectChanges();
    tick(0);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const values = compiled.querySelectorAll('.stat-value');
    expect(values[0].textContent?.trim()).toBe('2045');
    expect(values[1].textContent?.trim()).toBe('42');
    expect(values[2].textContent?.trim()).toBe('$1.2M');
    expect(compiled.querySelector('app-skeleton')).toBeNull();
    expect(compiled.querySelector('.stat-strip')!.getAttribute('aria-busy')).toBeNull();
  }));

  it('runs onTabActivated (freedomStats refresh) when the tab is activated', fakeAsync(() => {
    const freedomStatsMock = makeFreedomStatsServiceMock(
      false,
      { freedomYear: '2045', freedomAge: '42', dollarsAway: '$1.2M' }
    );

    TestBed.configureTestingModule({
      imports: [Tab3Page, RouterTestingModule],
      providers: [
        { provide: ModalController, useValue: makeModalControllerMock() }
      ]
    })
      .overrideProvider(SettingsService, { useValue: makeSettingsServiceMock() })
      .overrideProvider(PlaidService, { useValue: makePlaidServiceMock() })
      .overrideProvider(AuthService, { useValue: makeAuthServiceMock() })
      .overrideProvider(ToastService, { useValue: makeToastServiceMock() })
      .overrideProvider(MfuStoreService, { useValue: makeMfuStoreMock() })
      .overrideProvider(MfuPresenterService, { useValue: makeMfuPresenterMock() })
      .overrideProvider(AppLockService, { useValue: makeAppLockServiceMock() })
      .overrideProvider(AccountStatusService, { useValue: makeAccountStatusServiceMock() })
      .overrideProvider(FreedomStatsService, { useValue: freedomStatsMock });

    const fixture: ComponentFixture<Tab3Page> = TestBed.createComponent(Tab3Page);
    fixture.detectChanges();
    tick(0);

    const component = fixture.componentInstance;
    component.onTabActivated();

    expect(freedomStatsMock.refresh).toHaveBeenCalled();
  }));

  it('runs onTabActivated when TabActivationService activates tab3', fakeAsync(() => {
    const freedomStatsMock = makeFreedomStatsServiceMock(
      false,
      { freedomYear: '2045', freedomAge: '42', dollarsAway: '$1.2M' }
    );

    TestBed.configureTestingModule({
      imports: [Tab3Page, RouterTestingModule],
      providers: [
        { provide: ModalController, useValue: makeModalControllerMock() }
      ]
    })
      .overrideProvider(SettingsService, { useValue: makeSettingsServiceMock() })
      .overrideProvider(PlaidService, { useValue: makePlaidServiceMock() })
      .overrideProvider(AuthService, { useValue: makeAuthServiceMock() })
      .overrideProvider(ToastService, { useValue: makeToastServiceMock() })
      .overrideProvider(MfuStoreService, { useValue: makeMfuStoreMock() })
      .overrideProvider(MfuPresenterService, { useValue: makeMfuPresenterMock() })
      .overrideProvider(AppLockService, { useValue: makeAppLockServiceMock() })
      .overrideProvider(AccountStatusService, { useValue: makeAccountStatusServiceMock() })
      .overrideProvider(FreedomStatsService, { useValue: freedomStatsMock });

    const fixture = TestBed.createComponent(Tab3Page);
    fixture.detectChanges();
    tick(0);

    // never-on-mount: on the default tab (tab1) Profile's enter-work must NOT run
    expect(freedomStatsMock.refresh).not.toHaveBeenCalled();

    const activation = TestBed.inject(TabActivationService);
    activation.setActive('tab3');
    fixture.detectChanges();
    expect(freedomStatsMock.refresh).toHaveBeenCalledTimes(1);

    // re-pulse: returning to Profile (same tab) must re-run enter-work despite dedup
    activation.setActive('tab3');
    fixture.detectChanges();
    expect(freedomStatsMock.refresh).toHaveBeenCalledTimes(2);
  }));

  it('liveEquity is updated from equity signal', fakeAsync(() => {
    const freedomStatsMock = makeFreedomStatsServiceMock(
      false,
      { freedomYear: '2045', freedomAge: '42', dollarsAway: '$1.2M' },
      75000
    );

    TestBed.configureTestingModule({
      imports: [Tab3Page, RouterTestingModule],
      providers: [
        { provide: ModalController, useValue: makeModalControllerMock() }
      ]
    })
      .overrideProvider(SettingsService, { useValue: makeSettingsServiceMock() })
      .overrideProvider(PlaidService, { useValue: makePlaidServiceMock() })
      .overrideProvider(AuthService, { useValue: makeAuthServiceMock() })
      .overrideProvider(ToastService, { useValue: makeToastServiceMock() })
      .overrideProvider(MfuStoreService, { useValue: makeMfuStoreMock() })
      .overrideProvider(MfuPresenterService, { useValue: makeMfuPresenterMock() })
      .overrideProvider(AppLockService, { useValue: makeAppLockServiceMock() })
      .overrideProvider(AccountStatusService, { useValue: makeAccountStatusServiceMock() })
      .overrideProvider(FreedomStatsService, { useValue: freedomStatsMock });

    const fixture: ComponentFixture<Tab3Page> = TestBed.createComponent(Tab3Page);
    fixture.detectChanges();
    tick(0);
    fixture.detectChanges();

    const component = fixture.componentInstance;
    expect(component.liveEquity).toBe(75000);
  }));

});
