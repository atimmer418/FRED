import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { LoadingController, ModalController, NavController } from '@ionic/angular';
import { Subject, of, throwError } from 'rxjs';
import { TaxDocumentsPage } from './tax-documents.page';
import { SkeletonComponent } from '../../components/skeleton/skeleton.component';
import { PortfolioService } from '../../services/portfolio.service';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';

/**
 * Analytics call sites on tax-documents.
 *
 * A statement is a PDF of the person's whole account, so nothing about the document itself may be
 * sent: not its id, not its type, not the year. The category 'document' says a statement or 1099
 * failed to open, and that is the whole payload.
 */
describe('TaxDocumentsPage — analytics', () => {
  let analytics: jasmine.SpyObj<AnalyticsService>;
  let portfolio: jasmine.SpyObj<PortfolioService>;
  let modalCtrl: jasmine.SpyObj<ModalController>;
  let page: TaxDocumentsPage;

  const doc = { id: 'doc-1', type: 'account_statement', date: '2026-09-30', year: 2026 };

  /** Lets real FileReader work finish, since the PDF header check is read asynchronously. */
  async function waitUntil(done: () => boolean): Promise<void> {
    for (let attempt = 0; attempt < 50 && !done(); attempt++) {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    }
  }

  beforeEach(() => {
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', [
      'track',
      'trackOnboardingStepViewed',
      'trackOnboardingStepCompleted',
      'trackErrorShown',
      'screen',
    ]);
    portfolio = jasmine.createSpyObj<PortfolioService>('PortfolioService', ['getTaxDocuments', 'downloadDocument']);

    const loadingCtrl = jasmine.createSpyObj<LoadingController>('LoadingController', ['create']);
    loadingCtrl.create.and.resolveTo({
      present: () => Promise.resolve(),
      dismiss: () => Promise.resolve(true),
    } as unknown as HTMLIonLoadingElement);

    modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    modalCtrl.create.and.resolveTo({
      present: () => Promise.resolve(),
    } as unknown as HTMLIonModalElement);

    page = new TaxDocumentsPage(
      portfolio,
      loadingCtrl,
      jasmine.createSpyObj<ToastService>('ToastService', ['showToast']),
      modalCtrl,
      jasmine.createSpyObj<NavController>('NavController', ['navigateBack']),
      analytics,
    );
  });

  it('an empty document reports document, with no status because the download succeeded', async () => {
    portfolio.downloadDocument.and.returnValue(of(new Blob([])));

    await page.openDocument(doc);

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('document', 'portfolio', 'toast', null, false);
  });

  it('a download that is not a PDF reports document', async () => {
    portfolio.downloadDocument.and.returnValue(of(new Blob(['<html>login required</html>'])));

    await page.openDocument(doc);
    await waitUntil(() => analytics.trackErrorShown.calls.any());

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('document', 'portfolio', 'toast', null, false);
  });

  it('a failed download reports document with its status', async () => {
    portfolio.downloadDocument.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));

    await page.openDocument(doc);

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('document', 'portfolio', 'toast', 404, false);
  });

  it('a download that never reached the server reports network', async () => {
    portfolio.downloadDocument.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));

    await page.openDocument(doc);

    expect(analytics.trackErrorShown).toHaveBeenCalledOnceWith('network', 'portfolio', 'toast', null, false);
  });

  it('a real PDF fires nothing', async () => {
    portfolio.downloadDocument.and.returnValue(of(new Blob(['%PDF-1.7 trailer'])));

    await page.openDocument(doc);
    await waitUntil(() => modalCtrl.create.calls.any());

    expect(modalCtrl.create).toHaveBeenCalled();
    expect(analytics.trackErrorShown).not.toHaveBeenCalled();
  });
});

/**
 * The loading skeleton: while the documents call is pending the sheet is busy and shows the two
 * section cards as grey blocks (header, subtitle, two rows each) with none of the real content;
 * once the documents land the skeleton is gone and the real cards stand in its place. Karma has
 * no layout, so this asserts presence, not size.
 */
describe('TaxDocumentsPage: loading skeleton', () => {
  let fixture: ComponentFixture<TaxDocumentsPage>;
  let docs$: Subject<unknown[]>;

  /** Mounts the page with the documents call still pending. */
  function mount(): void {
    docs$ = new Subject<unknown[]>();
    const portfolio = jasmine.createSpyObj<PortfolioService>('PortfolioService', ['getTaxDocuments', 'downloadDocument']);
    portfolio.getTaxDocuments.and.returnValue(docs$.asObservable());
    if (!jasmine.isSpy(window.matchMedia)) {
      spyOn(window, 'matchMedia').and.returnValue({ matches: true } as MediaQueryList);
    }

    TestBed.configureTestingModule({
      imports: [TaxDocumentsPage],
      providers: [
        { provide: PortfolioService, useValue: portfolio },
        { provide: LoadingController, useValue: jasmine.createSpyObj<LoadingController>('LoadingController', ['create']) },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['showToast']) },
        { provide: ModalController, useValue: jasmine.createSpyObj<ModalController>('ModalController', ['create']) },
        { provide: NavController, useValue: jasmine.createSpyObj<NavController>('NavController', ['navigateBack']) },
        { provide: AnalyticsService, useValue: jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown']) },
      ],
    })
      // Ionic's elements render as plain custom elements; the skeleton is the real thing.
      .overrideComponent(TaxDocumentsPage, {
        set: { imports: [CommonModule, SkeletonComponent], schemas: [CUSTOM_ELEMENTS_SCHEMA] },
      });
    fixture = TestBed.createComponent(TaxDocumentsPage);
    fixture.detectChanges();
  }

  /** Lands the documents and renders the result. */
  function land(docs: unknown[]): void {
    docs$.next(docs);
    docs$.complete();
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;
  const busy = () => root().querySelector('[aria-busy="true"]');
  const skeletons = () => root().querySelectorAll('app-skeleton');
  const cards = () => root().querySelectorAll('.section-card');

  it('while loading, the sheet is busy and shows two skeleton cards with no real content', () => {
    mount();
    expect(busy()).toBe(root().querySelector('.shell-inner'));
    expect(cards().length).toBe(2);
    expect(skeletons().length).toBeGreaterThan(0);
    expect(root().querySelectorAll('.sk-block').length).toBeGreaterThan(0);

    // Each card: a header pair, a three-line subtitle, two rows in the real row classes.
    cards().forEach(card => {
      expect(card.querySelectorAll('.card-header-row app-skeleton').length).toBe(2);
      expect(card.querySelectorAll('app-skeleton.sk-subtitle .sk-block').length).toBe(3);
    });
    expect(cards()[0].querySelectorAll('.doc-list .doc-row').length).toBe(2);
    expect(cards()[1].querySelectorAll('.doc-list .year-header').length).toBe(2);

    // Nothing real yet.
    expect(root().querySelector('.card-title')).toBeNull();
    expect(root().querySelector('.card-subtitle')).toBeNull();
    expect(root().querySelector('button.doc-row')).toBeNull();
    expect(root().querySelector('button.year-header')).toBeNull();
    expect(root().querySelector('.empty-state')).toBeNull();
    expect(root().querySelector('.empty-inline')).toBeNull();
    expect(root().querySelector('ion-spinner')).toBeNull();
  });

  it('once the documents land, the skeleton is gone and the real cards stand in its place', () => {
    mount();
    land([
      { id: 'd1', type: 'tax_form_1099', date: '2025-02-10' },
      { id: 'd2', type: 'account_statement', date: '2026-09-30' },
    ]);
    expect(busy()).toBeNull();
    expect(skeletons().length).toBe(0);
    expect(root().querySelectorAll('.sk-block').length).toBe(0);
    expect(cards().length).toBe(2);
    expect(Array.from(root().querySelectorAll('.card-title')).map(t => t.textContent!.trim())).toEqual(['Tax Documents', 'Account Statements']);
    expect(root().querySelectorAll('button.doc-row').length).toBe(1);
    expect(root().querySelector('button.doc-row .doc-label')!.textContent).toBe('2025');
    expect(root().querySelectorAll('button.year-header').length).toBe(1);
    expect(root().querySelector('button.year-header .year-label')!.textContent).toBe('2026');
  });

  it('with no documents the skeleton gives way to the empty states, not to more skeleton', () => {
    mount();
    land([]);
    expect(busy()).toBeNull();
    expect(skeletons().length).toBe(0);
    expect(root().querySelector('.empty-state .empty-title')!.textContent).toBe('No Documents Yet');
    expect(root().querySelectorAll('.empty-inline').length).toBe(2);
  });
});
