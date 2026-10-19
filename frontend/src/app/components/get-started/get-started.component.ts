import { Component, ViewChild, ElementRef, AfterViewInit, OnInit, CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IonContent } from '@ionic/angular/standalone';
import { NavController, createAnimation } from '@ionic/angular';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import type { SwiperContainer } from 'swiper/element';

@Component({
  selector: 'app-get-started',
  templateUrl: './get-started.component.html',
  styleUrls: ['./get-started.component.scss'],
  standalone: true,
  imports: [CommonModule, IonContent],
  schemas: [CUSTOM_ELEMENTS_SCHEMA]
})
export class GetStartedComponent implements OnInit, AfterViewInit {
  @ViewChild('swiper') swiperRef: ElementRef<SwiperContainer> | undefined;

  private preloadImg?: HTMLImageElement;

  constructor(
    private navController: NavController,
    private authService: AuthService,
    private analytics: AnalyticsService
  ) { }

  ngOnInit() {
    // A stored onboardingStep means this device has already been through the first step, so this
    // arrival is a return rather than a fresh install walking in.
    this.analytics.trackOnboardingStepViewed('get_started', null, this.hasStoredOnboardingStep());
  }

  /** Storage can throw (private mode, blocked site data); analytics must never break the screen. */
  private hasStoredOnboardingStep(): boolean {
    try {
      return !!localStorage.getItem('onboardingStep');
    } catch {
      return false;
    }
  }

  ngAfterViewInit() {
    // Preload starts immediately on mount, regardless of which slide the user is on.
    // The hidden <img class="hidden-preload"> in the template is the primary mechanism;
    // this also calls decode() so the image is GPU-ready when survey-initial paints.
    this.preloadSurveyInitialAssets();
  }

  private async preloadSurveyInitialAssets() {
    this.preloadImg = new Image();
    this.preloadImg.src = '/assets/images/whenPiggybanksFly-3.jpg';
    try {
      await this.preloadImg.decode();
    } catch {
      // ignore — the <img> tag in the template still handles fetch+decode
    }
    document.fonts.load('700 16px "Manrope"').catch(() => {});
    document.fonts.load('400 24px "Material Symbols Outlined"').catch(() => {});
  }

  getSetUp() {
    // At the tap, not in the completeStep callback: the progress write is fire-and-forget and the
    // app navigates either way, so waiting for it would undercount.
    this.analytics.trackOnboardingStepCompleted('get_started', null);

    localStorage.setItem('onboardingStep', '1');

    this.navController.navigateRoot('/survey-initial', {
      animated: true,
      animation: (baseEl: any, opts: any) => {
        const leaveEl = opts.leavingEl;
        const enterEl = opts.enteringEl;

        // Keep the entering page behind the leaving page during the fade.
        enterEl.style.zIndex = '1';
        leaveEl.style.zIndex = '2';

        return createAnimation()
          .duration(3000)
          .easing('ease-out')
          .addAnimation([
            // Get-started fades out — user sees it disappearing
            createAnimation().addElement(leaveEl).fromTo('opacity', '1', '0'),
            // Survey-initial sits fully visible underneath from the start
            createAnimation().addElement(enterEl).fromTo('opacity', '1', '1'),
          ]);
      }
    });

    this.authService.completeStep('getStarted').subscribe({
      next: () => console.log('GetStarted step completed successfully'),
      error: (err) => console.log('GetStarted step could not be completed (likely not authenticated yet):', err)
    });
  }
}
