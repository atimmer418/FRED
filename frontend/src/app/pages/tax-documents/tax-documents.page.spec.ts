import { HttpErrorResponse } from '@angular/common/http';
import { LoadingController, ModalController, NavController } from '@ionic/angular';
import { of, throwError } from 'rxjs';
import { TaxDocumentsPage } from './tax-documents.page';
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
