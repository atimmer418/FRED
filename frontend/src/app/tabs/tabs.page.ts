import {
  Component, OnInit, AfterViewInit, NgZone, Optional, ViewChild, ElementRef, signal, CUSTOM_ELEMENTS_SCHEMA,
} from '@angular/core';
import { CommonModule, AsyncPipe, Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Observable } from 'rxjs';
import type { SwiperContainer } from 'swiper/element';
import type Swiper from 'swiper';
import type { SwiperOptions } from 'swiper/types';
import { IonRouterOutlet } from '@ionic/angular/standalone';
import {
  DragSample, SETTLE_DEFAULT_MS, pruneSamples, releaseVelocity, settleDurationMs,
} from '../utils/swipe-settle.utils';
import { AccountStatusService } from '../services/account-status.service';
import { AuthService } from '../services/auth.service';
import { TabBarScrollService } from '../services/tab-bar-scroll.service';
import { TabActivationService } from '../services/tab-activation.service';
import { FloatingTabBarComponent } from '../components/floating-tab-bar/floating-tab-bar.component';
import { Tab1Page } from '../tab1/tab1.page';
import { Tab2Page } from '../tab2/tab2.page';
import { AiChatPage } from '../pages/ai-chat/ai-chat.page';
import { Tab3Page } from '../tab3/tab3.page';

const TAB_ORDER = ['tab1', 'tab2', 'chat', 'tab3'] as const;

@Component({
  selector: 'app-tabs',
  templateUrl: 'tabs.page.html',
  styleUrls: ['tabs.page.scss'],
  standalone: true,
  imports: [
    CommonModule, AsyncPipe, FormsModule,
    FloatingTabBarComponent,
    Tab1Page, Tab2Page, AiChatPage, Tab3Page,
  ],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
})
export class TabsPage implements OnInit, AfterViewInit {
  @ViewChild('swiper') swiperRef?: ElementRef<SwiperContainer>;

  // The chat composer is rendered HERE (outside <swiper-container>, see
  // tabs.page.html) rather than inside AiChatPage's own <ion-footer> — see the
  // big comment on .chat-composer-portal in tabs.page.scss for why. AiChatPage
  // is still eagerly mounted as a pager slide, so its instance (and all its
  // message/session/streaming state) is reached directly through this
  // ViewChild; the composer template below just binds to its public members.
  @ViewChild(AiChatPage) chatPage?: AiChatPage;

  settingsTabBadge$: Observable<string | null>;
  isSubExpired = false;
  initialIndex = 0;
  readonly activeTab = signal<string>('tab1');

  /** Last tab whose settle COMPLETED — guards onTransitionEnd re-fires. */
  private settledTab = 'tab1';
  /** Touch positions inside the velocity window of the current drag. */
  private dragSamples: DragSample[] = [];
  /** Velocity + position snapshot taken at finger-up, consumed by the settle. */
  private pendingRelease: { velocity: number; translate: number; t: number } | null = null;

  constructor(
    private accountStatusService: AccountStatusService,
    private authService: AuthService,
    private scrollSvc: TabBarScrollService,
    private tabActivation: TabActivationService,
    private route: ActivatedRoute,
    private location: Location,
    private zone: NgZone,
    // The root ion-router-outlet hosting this page (null in unit tests).
    @Optional() private outlet: IonRouterOutlet | null,
  ) {
    this.settingsTabBadge$ = this.accountStatusService.settingsTabBadge$;
  }

  ngOnInit() {
    const initialTab = this.resolveTab(this.route.snapshot.paramMap.get('tab'));
    this.activeTab.set(initialTab);
    this.settledTab = initialTab;
    this.initialIndex = this.indexForTab(initialTab);

    this.accountStatusService.refreshStatus();

    this.authService.userProgress$.subscribe((progress) => {
      const fullyOnboarded = progress?.investmentConfirmationCompleted === true;
      const hasActiveSub = progress?.selectedTier != null;
      const isPrivateBeta = progress?.privateBeta === true;
      // Private beta users bypass the subscription gate.
      this.isSubExpired = fullyOnboarded && !hasActiveSub && !isPrivateBeta;
      this.applySwipeLock();
    });

    // External navigations to /tabs/<tab> (e.g. AppLock re-nav) move the pager.
    this.route.paramMap.subscribe((params) => {
      const tab = this.resolveTab(params.get('tab'));
      if (tab !== this.activeTab()) {
        this.slideToTab(tab);
      }
    });

    // Tour-driven pager moves (see TabActivationService.requestTab).
    this.tabActivation.tabRequest$.subscribe((tab) => this.slideToTab(tab));
  }

  ngAfterViewInit() {
    // initialSlide puts the pager on the right tab; activation is fired from
    // ionViewWillEnter, which also runs on the first enter.
    this.initSwiper();
    this.applySwipeLock();
  }

  /**
   * The pager is initialized manually (init="false" in the template) for two
   * reasons:
   * - OUTSIDE Angular's zone: Swiper registers document-level pointer/touch
   *   listeners at init, and listeners registered in-zone re-enter NgZone on
   *   every drag frame — a full change-detection pass across all four
   *   eagerly-mounted tabs per finger movement.
   * - to attach the velocity hooks below, which make the post-release settle
   *   inherit the finger's speed instead of playing Swiper's fixed-duration
   *   transition (the model lives in swipe-settle.utils.ts).
   * The hooks run outside the zone too — pure math, no Angular state. Angular
   * re-enters through the template's (swiperslidechange)/(swipertransitionend)
   * bindings, which fire once per swipe, not once per frame.
   */
  private initSwiper() {
    const el = this.swiperRef?.nativeElement;
    // Element not upgraded (unit tests): the pager simply stays inert.
    if (!el || typeof el.initialize !== 'function') {
      return;
    }
    const params: SwiperOptions = {
      initialSlide: this.initialIndex,
      slidesPerView: 1,
      speed: SETTLE_DEFAULT_MS,
      on: {
        sliderMove: (swiper) => this.recordDragSample(swiper),
        touchEnd: (swiper) => this.captureRelease(swiper),
        beforeTransitionStart: (swiper) => this.applyVelocitySettle(swiper),
      },
    };
    Object.assign(el, params);
    this.zone.runOutsideAngular(() => el.initialize());
  }

  /** Outside the Angular zone, once per tracked drag frame. */
  private recordDragSample(swiper: Swiper) {
    const now = performance.now();
    this.dragSamples = pruneSamples(this.dragSamples, now);
    this.dragSamples.push({ x: swiper.touches.currentX, t: now });
  }

  /** Snapshot velocity + position at finger-up, before Swiper picks a target. */
  private captureRelease(swiper: Swiper) {
    const now = performance.now();
    this.pendingRelease = {
      velocity: releaseVelocity(pruneSamples(this.dragSamples, now)),
      translate: swiper.translate,
      t: now,
    };
    this.dragSamples = [];
  }

  /**
   * Swiper emits beforeTransitionStart right after setTransition/setTranslate,
   * so the wrapper already carries params.speed and the target translate;
   * overriding transition-duration here is what velocity-matches the settle.
   * Applies only to the settle immediately following a release — programmatic
   * slides (tab-bar taps, route navs) keep the default timing, and a stale
   * pendingRelease from a drag that triggered no transition is aged out.
   */
  private applyVelocitySettle(swiper: Swiper) {
    const release = this.pendingRelease;
    this.pendingRelease = null;
    if (!release || performance.now() - release.t > 100 || !swiper.wrapperEl) {
      return;
    }
    const remaining = swiper.translate - release.translate;
    if (Math.abs(remaining) < 1) {
      return;
    }
    const toTarget = release.velocity * Math.sign(remaining);
    const ms = settleDurationMs(Math.abs(remaining), toTarget);
    swiper.wrapperEl.style.transitionDuration = `${ms}ms`;
  }

  /**
   * Fires on first enter AND on re-entry from a pushed sub-page — TabsPage is
   * cached in the root ion-router-outlet, so ngOnInit/ngAfterViewInit do not
   * re-run and the :tab param is unchanged on a navigateBack('/tabs/tab3').
   * Re-pulse activation so the current tab re-runs its on-enter work (e.g.
   * Profile's freedom-stats refresh + MFU check). The equal:()=>false activation
   * signal makes this same-tab re-pulse actually re-notify the tab effects.
   */
  ionViewWillEnter() {
    this.emitActivation(this.activeTab());
  }

  /**
   * Fires when the active slide changes — i.e. at the START of the settle
   * animation. Only the floating-bar indicator reacts here; URL sync and tab
   * activation (which kick off HTTP refreshes, menu state, a CD pass) are
   * deferred to onTransitionEnd so nothing heavy competes with the animation.
   */
  onSlideChange(event: Event) {
    const swiper = (event.target as SwiperContainer).swiper;
    const tab = TAB_ORDER[swiper?.activeIndex ?? 0] ?? 'tab1';
    if (tab === this.activeTab()) {
      return;
    }
    // Defer the signal write (and the change-detection pass it triggers) to a
    // microtask. Swiper fires slideChange synchronously BETWEEN setTranslate and
    // beforeTransitionStart; setting activeTab in-zone right here runs CD in that
    // exact gap, and if anything in that CD ever reads layout it commits the
    // settle transition at the DEFAULT duration before applyVelocitySettle can
    // override it — a silent no-op that quietly reverts the velocity-matched
    // feel. Running it one microtask later lets the duration override land first;
    // zone.run keeps CD firing so the floating-bar indicator still updates
    // (imperceptibly later, same frame).
    queueMicrotask(() => this.zone.run(() => this.activeTab.set(tab)));
  }

  /** Fires when a settle COMPLETES (drag release or programmatic slideTo). */
  onTransitionEnd(event: Event) {
    const swiper = (event.target as SwiperContainer).swiper;
    const tab = TAB_ORDER[swiper?.activeIndex ?? 0] ?? 'tab1';
    if (tab === this.settledTab) {
      return;
    }
    this.settledTab = tab;
    // replaceState updates the URL WITHOUT a router navigation, so it does not
    // re-trigger paramMap — no feedback loop, no guard flag needed.
    this.location.replaceState('/tabs/' + tab);
    // But bypassing the router means Ionic's stack never learns the new tab:
    // the RouteView URL is stamped once at view creation and read back by the
    // edge-swipe-back pop (and by the cached-view lookup on navigateBack).
    // Re-stamp it so returning from a pushed sub-page lands on THIS tab
    // instead of the cold-start tab, reusing the cached TabsPage.
    const stackView = this.outlet?.activatedView;
    if (stackView) {
      stackView.url = '/tabs/' + tab;
    }
    this.emitActivation(tab);
  }

  /** Floating-bar tap. slideTo triggers onSlideChange, which does the rest. */
  selectTab(tab: string) {
    this.slideToTab(tab);
  }

  private emitActivation(tab: string) {
    this.scrollSvc.reset();
    // Chat holds the bar full so no stray/cross-tab scroll can shrink it there.
    this.scrollSvc.setHoldFull(tab === 'chat');
    this.tabActivation.setActive(tab);
  }

  private slideToTab(tab: string, animate = true) {
    const swiper = this.swiperRef?.nativeElement.swiper;
    if (!swiper) {
      return;
    }
    swiper.slideTo(this.indexForTab(tab), animate ? undefined : 0);
  }

  private applySwipeLock() {
    const swiper = this.swiperRef?.nativeElement.swiper;
    if (swiper) {
      // Expired subscription: gated tabs are unreachable by swipe; Profile is
      // still reachable by tapping it (programmatic slideTo ignores this flag).
      swiper.allowTouchMove = !this.isSubExpired;
    }
  }

  private indexForTab(tab: string): number {
    const i = TAB_ORDER.indexOf(tab as (typeof TAB_ORDER)[number]);
    return i < 0 ? 0 : i;
  }

  private resolveTab(param: string | null): string {
    return param && (TAB_ORDER as readonly string[]).includes(param) ? param : 'tab1';
  }
}
