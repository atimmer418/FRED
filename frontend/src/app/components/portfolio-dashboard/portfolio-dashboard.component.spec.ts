import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { ModalController } from '@ionic/angular/standalone';
import { BehaviorSubject, Observable, Subject, throwError } from 'rxjs';

import { PortfolioDashboardComponent } from './portfolio-dashboard.component';
import { SkeletonComponent } from '../skeleton/skeleton.component';
import { PortfolioDashboardData, PortfolioService } from '../../services/portfolio.service';
import { PortfolioBundle, PortfolioSnapshot, PortfolioStoreService } from '../../services/portfolio-store.service';
import { ToastService } from '../../services/toast.service';
import { AuthService } from '../../services/auth.service';
import { MfuPresenterService } from '../../services/mfu-presenter.service';

function dashboardData(): PortfolioDashboardData {
  return {
    summary: {
      portfolioValue: 1234.56, todayChange: 0, todayChangePercent: 0,
      buyingPower: 0, cash: 0, withdrawableCash: 0, equity: 1234.56,
    },
    positions: [{
      symbol: 'VTI', name: 'Vanguard Total Stock Market', quantity: 4, quantityAvailable: 4,
      marketValue: 1234.56, costBasis: 1000, unrealizedPL: 234.56, unrealizedPLPercent: 23.46,
      currentPrice: 308.64, averageCostBasis: 250, percentOfAccount: 100, todayGainLoss: 0, todayGainLossPercent: 0,
    }],
    history: { timestamps: [], values: [] },
    recentTransactions: [],
    totalInvested: 1000,
    totalGainLoss: 234.56,
    totalGainLossPercent: 23.46,
  };
}

function bundle(): PortfolioBundle {
  return { dashboard: dashboardData(), performance: [], history: null };
}

function snapshot(): PortfolioSnapshot {
  return { userId: '42', savedAt: Date.now() - 60_000, period: 'ALL', dashboard: dashboardData(), performance: [], history: null, freedomLabel: '' };
}

describe('PortfolioDashboardComponent', () => {
  let fixture: ComponentFixture<PortfolioDashboardComponent>;
  let store: jasmine.SpyObj<PortfolioStoreService>;
  let presentAutoIfDue: jasmine.Spy;

  const root = () => fixture.nativeElement as HTMLElement;
  const q = (selector: string) => root().querySelector(selector);
  const count = (selector: string) => root().querySelectorAll(selector).length;

  /** Mounts the dashboard against a store whose first load returns `load$`, optionally over a persisted snapshot. */
  async function create(load$: Observable<PortfolioBundle>, snap: PortfolioSnapshot | null = null): Promise<void> {
    store = jasmine.createSpyObj<PortfolioStoreService>('PortfolioStoreService', ['peekSnapshot', 'load$', 'persistSnapshot']);
    store.peekSnapshot.and.returnValue(snap);
    store.load$.and.returnValue(load$);
    presentAutoIfDue = jasmine.createSpy('presentAutoIfDue').and.resolveTo();

    await TestBed.configureTestingModule({
      imports: [PortfolioDashboardComponent],
      providers: [
        { provide: PortfolioStoreService, useValue: store },
        { provide: PortfolioService, useValue: jasmine.createSpyObj<PortfolioService>('PortfolioService', ['getPortfolioHistory']) },
        { provide: ToastService, useValue: { showToast: jasmine.createSpy('showToast') } },
        { provide: AuthService, useValue: { userProgress$: new BehaviorSubject(null) } },
        { provide: MfuPresenterService, useValue: { presentAutoIfDue } },
        { provide: ModalController, useValue: jasmine.createSpyObj<ModalController>('ModalController', ['create']) },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
      ],
    })
      // Ionic standalone components and the chart need their own providers; render them as plain DOM.
      .overrideComponent(PortfolioDashboardComponent, {
        set: { imports: [CommonModule, FormsModule, SkeletonComponent], schemas: [CUSTOM_ELEMENTS_SCHEMA] },
      })
      .compileComponents();

    fixture = TestBed.createComponent(PortfolioDashboardComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('shows the skeleton with aria-busy while the first load is in flight, then the fresh dashboard with no skeleton', async () => {
    const pending = new Subject<PortfolioBundle>();
    await create(pending);

    expect(fixture.componentInstance.loading).toBeTrue();
    expect(q('[aria-busy="true"]')).not.toBeNull();
    expect(q('.sk-hero')).not.toBeNull();
    expect(count('app-skeleton')).toBeGreaterThan(0);
    // The refresher keeps its own spinner; the loading region has none.
    expect(q('.portfolio-skeleton ion-spinner')).toBeNull();
    expect(q('.portfolio-dashboard')).toBeNull();
    expect(q('.hero-wrap')).toBeNull();

    pending.next(bundle());
    pending.complete();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.loading).toBeFalse();
    expect(q('[aria-busy="true"]')).toBeNull();
    expect(count('app-skeleton')).toBe(0);
    expect(q('.portfolio-skeleton')).toBeNull();
    expect(q('.portfolio-dashboard[data-fresh]')).not.toBeNull();
    expect(q('.hero-wrap')).not.toBeNull();
    expect(presentAutoIfDue).toHaveBeenCalled();
  });

  it('the skeleton follows the tab switcher: the performance card on overview, two performance rows and three holding rows on analytics', async () => {
    await create(new Subject<PortfolioBundle>());

    expect(q('.sk-perf')).not.toBeNull();
    expect(count('.sk-chips app-skeleton')).toBe(5);
    // The return row's bars ride inline in the real text classes, so the row keeps its line box.
    expect(q('.sk-perf .return-row .rr-label.sk-inline app-skeleton')).not.toBeNull();
    expect(q('.sk-perf .return-row .rr-value.sk-inline app-skeleton')).not.toBeNull();
    expect(count('.sk-hold-row')).toBe(0);

    fixture.componentInstance.onTabChange({ detail: { value: 'analytics' } });
    fixture.detectChanges();

    expect(q('.sk-perf')).toBeNull();
    expect(count('.sk-perf-row')).toBe(2);
    // Each performance row's four bars sit inline in the real text classes (period, percent, the
    // two sub-line spans), so the row is as tall as its text will be.
    expect(count('.sk-perf-row .perf-top .perf-period.sk-inline app-skeleton')).toBe(2);
    expect(count('.sk-perf-row .perf-top .perf-pct.sk-inline app-skeleton')).toBe(2);
    expect(count('.sk-perf-row .perf-sub > .sk-inline app-skeleton')).toBe(4);
    expect(count('.sk-perf-row .perf-sub .perf-sub-gl.sk-inline app-skeleton')).toBe(2);
    // The show-more toggle row stands in too: the real card has it once the history is a week old.
    expect(q('.sk-perf-row.perf-row-nob + .sk-perf-toggle app-skeleton')).not.toBeNull();
    expect(count('.sk-hold-row')).toBe(3);
    expect(q('.sk-hold-row.hold-row-last')).not.toBeNull();
    // The symbol, name and value lines carry the real text classes; the pill keeps its own box.
    expect(count('.sk-hold-row .hold-mid .hold-sym.sk-inline app-skeleton')).toBe(3);
    expect(count('.sk-hold-row .hold-mid .hold-name.sk-inline app-skeleton')).toBe(3);
    expect(count('.sk-hold-row .hold-right .hold-val.sk-inline app-skeleton')).toBe(3);
    expect(count('.sk-hold-row .hold-right app-skeleton.sk-hold-pill')).toBe(3);
    // The totals footer stands in too: the real card always has it once positions exist.
    expect(q('.sk-hold-row.hold-row-last + .hold-total .t-label.sk-inline app-skeleton')).not.toBeNull();
    expect(q('.hold-total .t-right .t-val.sk-inline app-skeleton')).not.toBeNull();
    expect(q('.hold-total .t-right .t-gl.sk-inline app-skeleton')).not.toBeNull();
    expect(q('[aria-busy="true"]')).not.toBeNull();
  });

  it('a persisted snapshot paints at once with no skeleton and without data-fresh until the revalidate lands', async () => {
    const pending = new Subject<PortfolioBundle>();
    await create(pending, snapshot());

    expect(fixture.componentInstance.loading).toBeFalse();
    expect(count('app-skeleton')).toBe(0);
    expect(q('[aria-busy="true"]')).toBeNull();
    expect(q('.portfolio-dashboard')).not.toBeNull();
    expect(q('.portfolio-dashboard[data-fresh]')).toBeNull();
    expect(q('.snapshot-updating-marker')).not.toBeNull();

    pending.next(bundle());
    pending.complete();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(q('.portfolio-dashboard[data-fresh]')).not.toBeNull();
    expect(q('.snapshot-updating-marker')).toBeNull();
    expect(count('app-skeleton')).toBe(0);
    expect(store.persistSnapshot).toHaveBeenCalled();
  });

  it('a failed first load shows the error card, not the skeleton', async () => {
    await create(throwError(() => ({ error: { message: 'down' } })));

    expect(fixture.componentInstance.loading).toBeFalse();
    expect(q('.portfolio-error')).not.toBeNull();
    expect(count('app-skeleton')).toBe(0);
    expect(q('[aria-busy="true"]')).toBeNull();
    expect(q('.portfolio-dashboard')).toBeNull();
  });
});
