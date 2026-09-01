import {
  Component, OnInit, AfterViewInit, ViewChild, ElementRef, signal, CUSTOM_ELEMENTS_SCHEMA,
} from '@angular/core';
import { CommonModule, AsyncPipe, Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Observable } from 'rxjs';
import type { SwiperContainer } from 'swiper/element';
import { IonTextarea, IonButton, IonIcon } from '@ionic/angular/standalone';
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
    IonTextarea, IonButton, IonIcon,
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

  constructor(
    private accountStatusService: AccountStatusService,
    private authService: AuthService,
    private scrollSvc: TabBarScrollService,
    private tabActivation: TabActivationService,
    private route: ActivatedRoute,
    private location: Location,
  ) {
    this.settingsTabBadge$ = this.accountStatusService.settingsTabBadge$;
  }

  ngOnInit() {
    const initialTab = this.resolveTab(this.route.snapshot.paramMap.get('tab'));
    this.activeTab.set(initialTab);
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
  }

  ngAfterViewInit() {
    // Sit on the initial slide and apply the expired-sub lock. (Activation is
    // fired from ionViewWillEnter, which also runs on the first enter.)
    this.slideToTab(this.activeTab(), false);
    this.applySwipeLock();
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

  /** Swiper fires this on every settle (drag or programmatic slideTo). */
  onSlideChange(event: Event) {
    const swiper = (event.target as SwiperContainer).swiper;
    const tab = TAB_ORDER[swiper?.activeIndex ?? 0] ?? 'tab1';
    if (tab === this.activeTab()) {
      return;
    }
    this.activeTab.set(tab);
    // replaceState updates the URL WITHOUT a router navigation, so it does not
    // re-trigger paramMap — no feedback loop, no guard flag needed.
    this.location.replaceState('/tabs/' + tab);
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
