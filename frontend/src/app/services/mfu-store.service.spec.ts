/**
 * mfu-store.service.spec.ts — Stage 3 (MFU preload)
 *
 * Covers the single-flight session, the finalize-null retry (a /check error is NOT replayed), the
 * shell fallback on /generate error, the per-month auto-popup claim, the userId-scoped snapshot
 * round-trip, and logout clearing. Mocks MonthlyFreedomUpdateService so there is no live network.
 */

import { TestBed } from '@angular/core/testing';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';
import { MfuStoreService } from './mfu-store.service';
import { MonthlyFreedomUpdateService, MonthlyFreedomUpdateData } from './monthly-freedom-update.service';
import { AuthService } from './auth.service';

function data(partial: Partial<MonthlyFreedomUpdateData>): MonthlyFreedomUpdateData {
  return partial as unknown as MonthlyFreedomUpdateData;
}

describe('MfuStoreService', () => {
  let service: MfuStoreService;
  let mfuSpy: jasmine.SpyObj<MonthlyFreedomUpdateService>;
  let loggedIn$: BehaviorSubject<boolean>;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('userId', 'u1');
    mfuSpy = jasmine.createSpyObj('MonthlyFreedomUpdateService', ['checkShouldShow', 'generateUpdate', 'commitSeen', 'isMockActive']);
    mfuSpy.isMockActive.and.returnValue(false);
    loggedIn$ = new BehaviorSubject<boolean>(true);
    TestBed.configureTestingModule({
      providers: [
        MfuStoreService,
        { provide: MonthlyFreedomUpdateService, useValue: mfuSpy },
        { provide: AuthService, useValue: { isLoggedIn$: loggedIn$.asObservable() } },
      ]
    });
    service = TestBed.inject(MfuStoreService);
  });

  afterEach(() => localStorage.clear());

  it('single-flights concurrent ensureAutoSession callers (one /check)', () => {
    const check = new Subject<MonthlyFreedomUpdateData>();
    mfuSpy.checkShouldShow.and.returnValue(check.asObservable());
    let a: any, b: any;
    service.ensureAutoSession().subscribe(s => (a = s));
    service.ensureAutoSession().subscribe(s => (b = s));
    expect(mfuSpy.checkShouldShow).toHaveBeenCalledTimes(1);
    check.next(data({ shouldShow: false, hasMfuHistory: true }));
    check.complete();
    expect(a.shouldShow).toBeFalse();
    expect(b.shouldShow).toBeFalse();
    expect(mfuSpy.generateUpdate).not.toHaveBeenCalled();
  });

  it('chains /generate when shouldShow and returns preloaded data', () => {
    mfuSpy.checkShouldShow.and.returnValue(of(data({ shouldShow: true, hasMfuHistory: true })));
    mfuSpy.generateUpdate.and.returnValue(of(data({ periodStart: '2026-06-01', generatedForMonth: '2026-07' })));
    let s: any;
    service.ensureAutoSession().subscribe(x => (s = x));
    expect(mfuSpy.generateUpdate).toHaveBeenCalledWith(false);
    expect(s.shouldShow).toBeTrue();
    expect(s.data.generatedForMonth).toBe('2026-07');
  });

  it('caches a settled session (no second /check within TTL)', () => {
    mfuSpy.checkShouldShow.and.returnValue(of(data({ shouldShow: false, hasMfuHistory: false })));
    service.ensureAutoSession().subscribe();
    service.ensureAutoSession().subscribe();
    expect(mfuSpy.checkShouldShow).toHaveBeenCalledTimes(1);
  });

  it('does NOT cache a /check error — a later call retries', () => {
    mfuSpy.checkShouldShow.and.returnValue(throwError(() => new Error('401')));
    service.ensureAutoSession().subscribe({ next: () => {}, error: () => {} });
    service.ensureAutoSession().subscribe({ next: () => {}, error: () => {} });
    expect(mfuSpy.checkShouldShow).toHaveBeenCalledTimes(2);
  });

  it('resolves data:null (shell) when /generate errors but shouldShow is true', () => {
    mfuSpy.checkShouldShow.and.returnValue(of(data({ shouldShow: true, hasMfuHistory: true })));
    mfuSpy.generateUpdate.and.returnValue(throwError(() => new Error('500')));
    let s: any;
    service.ensureAutoSession().subscribe(x => (s = x));
    expect(s.shouldShow).toBeTrue();
    expect(s.data).toBeNull();
  });

  it('claims the auto popup once per month', () => {
    expect(service.tryClaimAutoPopup('2026-07')).toBeTrue();
    expect(service.tryClaimAutoPopup('2026-07')).toBeFalse();
    expect(service.tryClaimAutoPopup('2026-08')).toBeTrue();
  });

  it('round-trips the reopen snapshot (userId-scoped)', () => {
    service.persistSnapshot(data({ periodStart: '2026-06-01', generatedForMonth: '2026-07' }));
    expect(service.peekSnapshot()?.generatedForMonth).toBe('2026-07');
    localStorage.setItem('userId', 'other');
    expect(service.peekSnapshot()).toBeNull(); // a different user can't read it
  });

  it('peekSnapshot returns null on corrupt JSON', () => {
    localStorage.setItem('fred.mfu.v1.u1', '{not json');
    expect(service.peekSnapshot()).toBeNull();
  });

  it('does not persist a snapshot without periodStart', () => {
    service.persistSnapshot(data({ generatedForMonth: '2026-07' }));
    expect(service.peekSnapshot()).toBeNull();
  });

  it('DEV mock active: persistSnapshot writes nothing (a mocked recap must never hydrate later)', () => {
    mfuSpy.isMockActive.and.returnValue(true);
    service.persistSnapshot(data({ periodStart: '2026-08-01', generatedForMonth: '2026-09' }));
    expect(localStorage.getItem('fred.mfu.v1.u1')).toBeNull();
  });

  it('DEV mock active: peekSnapshot ignores a real snapshot stored earlier', () => {
    service.persistSnapshot(data({ periodStart: '2026-06-01', generatedForMonth: '2026-07' }));
    mfuSpy.isMockActive.and.returnValue(true);
    expect(service.peekSnapshot()).toBeNull();
    expect(localStorage.getItem('fred.mfu.v1.u1')).not.toBeNull(); // left in place for when the mock is off
  });

  it('loadReopen$ fetches with reopen=true and persists the snapshot', () => {
    mfuSpy.generateUpdate.and.returnValue(of(data({ periodStart: '2026-06-01', generatedForMonth: '2026-07' })));
    service.loadReopen$().subscribe();
    expect(mfuSpy.generateUpdate).toHaveBeenCalledWith(true);
    expect(service.peekSnapshot()?.generatedForMonth).toBe('2026-07');
  });

  it('clears in-memory session and claim on logout', () => {
    mfuSpy.checkShouldShow.and.returnValue(of(data({ shouldShow: false, hasMfuHistory: false })));
    service.ensureAutoSession().subscribe();
    service.tryClaimAutoPopup('2026-07');

    loggedIn$.next(false); // logout

    expect(service.tryClaimAutoPopup('2026-07')).toBeTrue(); // claim reset
    service.ensureAutoSession().subscribe();
    expect(mfuSpy.checkShouldShow).toHaveBeenCalledTimes(2); // cached session cleared → refetch
  });
});
