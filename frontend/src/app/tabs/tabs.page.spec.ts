import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { Location } from '@angular/common';
import { IonRouterOutlet } from '@ionic/angular/standalone';
import { of } from 'rxjs';
import { TabsPage } from './tabs.page';
import { Tab1Page } from '../tab1/tab1.page';
import { Tab2Page } from '../tab2/tab2.page';
import { AiChatPage } from '../pages/ai-chat/ai-chat.page';
import { Tab3Page } from '../tab3/tab3.page';
import { AccountStatusService } from '../services/account-status.service';
import { AuthService } from '../services/auth.service';
import { TabBarScrollService } from '../services/tab-bar-scroll.service';
import { TabActivationService } from '../services/tab-activation.service';

// TabsPage's template mounts all four tab pages unconditionally, as swiper slides —
// by design, so every tab stays "warm" and swiping never re-fetches/re-mounts. Ivy's
// creation pass instantiates unconditional template children synchronously, even
// without fixture.detectChanges() (only *ngIf/*ngFor-gated content is deferred to the
// update pass). So exercising the REAL Tab1Page/Tab2Page/AiChatPage/Tab3Page here would
// drag in their full transitive service graphs (HTTP, Ionic overlay controllers, MFU
// services, etc.) — none of which this spec is about. Swap them for inert stubs that
// share their selectors; TabsPage's own pager/activation/URL-sync logic is what's under
// test, and the real tab pages are already covered by their own specs.
@Component({ selector: 'app-tab1', template: '', standalone: true })
class StubTab1Page {}
@Component({ selector: 'app-tab2', template: '', standalone: true })
class StubTab2Page {}
@Component({ selector: 'app-ai-chat', template: '', standalone: true })
class StubAiChatPage {}
@Component({ selector: 'app-tab3', template: '', standalone: true })
class StubTab3Page {}

function makeAccountStatusMock() {
  return { settingsTabBadge$: of(null), refreshStatus: () => {} };
}
function makeAuthMock() {
  return { userProgress$: of(null) };
}

describe('TabsPage pager', () => {
  let scrollSvc: jasmine.SpyObj<TabBarScrollService>;

  function build(outlet?: Partial<IonRouterOutlet>) {
    scrollSvc = jasmine.createSpyObj('TabBarScrollService', ['reset', 'setHoldFull']);
    TestBed.configureTestingModule({
      imports: [TabsPage, RouterTestingModule],
      providers: [
        // TabsPage's outlet injection is @Optional — tests without a stub pin the
        // null-outlet path.
        ...(outlet ? [{ provide: IonRouterOutlet, useValue: outlet }] : []),
      ],
    })
      .overrideComponent(TabsPage, {
        remove: { imports: [Tab1Page, Tab2Page, AiChatPage, Tab3Page] },
        add: { imports: [StubTab1Page, StubTab2Page, StubAiChatPage, StubTab3Page] },
      })
      .overrideProvider(AccountStatusService, { useValue: makeAccountStatusMock() })
      .overrideProvider(AuthService, { useValue: makeAuthMock() })
      .overrideProvider(TabBarScrollService, { useValue: scrollSvc });
    const fixture = TestBed.createComponent(TabsPage);
    return fixture;
  }

  function swiperEvent(activeIndex: number): Event {
    return { target: { swiper: { activeIndex } } } as unknown as Event;
  }

  it('defaults to tab1 with initialIndex 0', () => {
    const c = build().componentInstance;
    c.ngOnInit();
    expect(c.activeTab()).toBe('tab1');
    expect(c.initialIndex).toBe(0);
  });

  // The slide-change moment is the start of the settle animation. Only the
  // tab-bar indicator may react there — URL sync and tab activation (HTTP
  // refreshes, menu state) are deferred to the settle END so they never run
  // change detection or network work while the pager is still animating.
  it('onSlideChange updates the active tab immediately and defers everything else', () => {
    const fixture = build();
    const c = fixture.componentInstance;
    c.ngOnInit();
    const location = TestBed.inject(Location);
    const activation = TestBed.inject(TabActivationService);
    const replaceSpy = spyOn(location, 'replaceState');
    const setActiveSpy = spyOn(activation, 'setActive');

    c.onSlideChange(swiperEvent(2));

    expect(c.activeTab()).toBe('chat');
    expect(replaceSpy).not.toHaveBeenCalled();
    expect(setActiveSpy).not.toHaveBeenCalled();
  });

  it('onTransitionEnd commits URL, activation and hold-full for chat on settle', () => {
    const fixture = build();
    const c = fixture.componentInstance;
    c.ngOnInit();
    const location = TestBed.inject(Location);
    const activation = TestBed.inject(TabActivationService);
    const replaceSpy = spyOn(location, 'replaceState');
    const setActiveSpy = spyOn(activation, 'setActive');

    c.onSlideChange(swiperEvent(2));
    c.onTransitionEnd(swiperEvent(2));

    expect(replaceSpy).toHaveBeenCalledWith('/tabs/chat');
    expect(setActiveSpy).toHaveBeenCalledWith('chat');
    expect(scrollSvc.setHoldFull).toHaveBeenCalledWith(true);
  });

  // Ionic's edge-swipe-back pops to the URL recorded on the stack's RouteView,
  // which is stamped once at view creation and is NOT refreshed by
  // location.replaceState. Without a re-stamp on slide settle, a swipe to tab3
  // followed by push + edge-swipe-back pops to the cold-start tab (tab1).
  it('onTransitionEnd re-stamps the Ionic stack view URL for swipe-back', () => {
    const view = { url: '/tabs/tab1' } as IonRouterOutlet['activatedView'];
    const c = build({ activatedView: view }).componentInstance;
    c.ngOnInit();

    c.onTransitionEnd(swiperEvent(3));

    expect(view!.url).toBe('/tabs/tab3');
  });

  // A snap-back (released before the halfway point) settles on the tab we were
  // already on; the settle-end handler must not re-fire URL sync or activation.
  it('onTransitionEnd ignores settles on the already-settled tab', () => {
    const fixture = build();
    const c = fixture.componentInstance;
    c.ngOnInit();
    const location = TestBed.inject(Location);
    const activation = TestBed.inject(TabActivationService);
    const replaceSpy = spyOn(location, 'replaceState');
    const setActiveSpy = spyOn(activation, 'setActive');

    c.onTransitionEnd(swiperEvent(0));

    expect(replaceSpy).not.toHaveBeenCalled();
    expect(setActiveSpy).not.toHaveBeenCalled();
  });

  it('selectTab moves the swiper to the tab index', () => {
    const c = build().componentInstance;
    c.ngOnInit();
    const slideTo = jasmine.createSpy('slideTo');
    (c as any).swiperRef = { nativeElement: { swiper: { slideTo, allowTouchMove: true } } };

    c.selectTab('tab3');
    expect(slideTo).toHaveBeenCalledWith(3, undefined);
  });

  // The first-time tour moves between tabs through TabActivationService rather
  // than by router-navigating to /tabs/<tab>: Ionic reuses views by their
  // stamped URL, so a route move to a different tab builds a SECOND TabsPage.
  it('slides the pager on a TabActivationService.requestTab', () => {
    const c = build().componentInstance;
    c.ngOnInit();
    const slideTo = jasmine.createSpy('slideTo');
    (c as any).swiperRef = { nativeElement: { swiper: { slideTo, allowTouchMove: true } } };

    TestBed.inject(TabActivationService).requestTab('chat');
    expect(slideTo).toHaveBeenCalledWith(2, undefined);
  });

  it('ionViewWillEnter re-pulses activation for the current tab', () => {
    const fixture = build();
    const c = fixture.componentInstance;
    c.ngOnInit();
    const activation = TestBed.inject(TabActivationService);
    const setActiveSpy = spyOn(activation, 'setActive');

    c.ionViewWillEnter();

    expect(setActiveSpy).toHaveBeenCalledWith('tab1');
    expect(scrollSvc.reset).toHaveBeenCalled();
  });
});
