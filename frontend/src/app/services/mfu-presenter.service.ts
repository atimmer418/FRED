import { Injectable } from '@angular/core';
import { ModalController, createAnimation } from '@ionic/angular/standalone';
import { Router } from '@angular/router';
import { filter, firstValueFrom, take } from 'rxjs';
import { MonthlyFreedomUpdateComponent } from '../components/monthly-freedom-update/monthly-freedom-update.component';
import { MonthlyFreedomUpdateData } from './monthly-freedom-update.service';
import { MfuStoreService, MfuAutoSession } from './mfu-store.service';
import { AppLockService } from './app-lock.service';
import { TabActivationService } from './tab-activation.service';
import { RetirementSectionService } from './retirement-section.service';

/**
 * Single owner of Monthly Freedom Update modal presentation. Both the dashboard (tab1) and tab3 call
 * this, so the modal-create + present-when-ready orchestration + the updateContribution navigation
 * live in ONE place and cannot drift.
 */
@Injectable({ providedIn: 'root' })
export class MfuPresenterService {
  constructor(
    private modalController: ModalController,
    private appLockService: AppLockService,
    private router: Router,
    private mfuStore: MfuStoreService,
    private tabActivation: TabActivationService,
    private retirementSection: RetirementSectionService
  ) {}

  /**
   * First-of-month auto popup, present-when-ready. The shared single-flight session + a LATE claim
   * guarantee exactly one modal across tab1 and tab3. There is no "Preparing your update…" state:
   * the payload and fonts are resolved BEFORE present(), so the modal opens fully painted.
   */
  async presentAutoIfDue(): Promise<void> {
    let session: MfuAutoSession;
    try {
      session = await firstValueFrom(this.mfuStore.ensureAutoSession());
    } catch {
      return; // transient /check error — not cached, so a later trigger retries
    }
    if (!session.shouldShow) return;

    // Resolve fonts + wait for any app-lock BEFORE claiming, so an unlock that never arrives can't
    // consume the one-shot claim (a later unlock/trigger can still present).
    await this.mfuStore.ensureFonts();
    await this.waitForUnlock();

    const month = session.data?.generatedForMonth || this.currentMonth();
    if (!this.mfuStore.tryClaimAutoPopup(month)) return;

    await this.presentModal(false, session.data);
  }

  /**
   * Reopen from the tab3 FRED logo: instant paint from the snapshot; the component then revalidates
   * the live/projection fields. On a cold cache miss (preloaded null) the component self-loads.
   */
  async presentReopen(): Promise<void> {
    const snap = this.mfuStore.peekSnapshot();
    await this.presentModal(true, snap);
  }

  private waitForUnlock(): Promise<void> {
    if (!this.appLockService.isCurrentlyLocked()) return Promise.resolve();
    return firstValueFrom(
      this.appLockService.isLocked$.pipe(filter(locked => !locked), take(1))
    ).then(() => undefined);
  }

  private currentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  }

  private async presentModal(isReopen: boolean, preloaded: MonthlyFreedomUpdateData | null): Promise<void> {
    const modal = await this.modalController.create({
      component: MonthlyFreedomUpdateComponent,
      componentProps: { isReopen, preloaded },
      cssClass: 'monthly-freedom-update-modal',
      backdropDismiss: isReopen, // reopen is dismissable by backdrop; the auto popup is not
      leaveAnimation: (baseEl: HTMLElement) => {
        const backdropEl = baseEl.querySelector('ion-backdrop') || baseEl.shadowRoot?.querySelector('ion-backdrop');
        const wrapperEl = baseEl.querySelector('.modal-wrapper') || baseEl.shadowRoot?.querySelector('.modal-wrapper') || baseEl;
        const backdropAnim = createAnimation()
          .addElement(backdropEl || baseEl)
          .fromTo('opacity', '1', '0')
          .easing('ease-in');
        const contentAnim = createAnimation()
          .addElement(wrapperEl)
          .fromTo('opacity', '1', '0')
          .fromTo('transform', 'translateY(0)', 'translateY(24px)')
          .easing('cubic-bezier(0.4, 0, 0.2, 1)');
        return createAnimation()
          .addElement(baseEl)
          .duration(400)
          .addAnimation([backdropAnim, contentAnim]);
      }
    });

    await modal.present();

    const { data } = await modal.onDidDismiss();
    if (data?.action === 'updateContribution') {
      const suggestedAmount = (data.currentAmount || 0) + (data.boostAmount || 50);
      this.router.navigate(['/recurring-investments'], { queryParams: { suggestedAmount } });
    } else if (data?.action === 'exploreRetirement') {
      // FRED-217 Retire card: slide the pager to tab2 and open its Education section.
      this.tabActivation.requestTab('tab2');
      this.retirementSection.requestSection('education');
    }
  }
}
