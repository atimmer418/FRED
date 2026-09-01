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

  function build() {
    scrollSvc = jasmine.createSpyObj('TabBarScrollService', ['reset', 'setHoldFull']);
    TestBed.configureTestingModule({
      imports: [TabsPage, RouterTestingModule],
      providers: [],
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

  it('defaults to tab1 with initialIndex 0', () => {
    const c = build().componentInstance;
    c.ngOnInit();
    expect(c.activeTab()).toBe('tab1');
    expect(c.initialIndex).toBe(0);
  });

  it('onSlideChange updates activeTab, URL, activation and hold-full for chat', () => {
    const fixture = build();
    const c = fixture.componentInstance;
    c.ngOnInit();
    const location = TestBed.inject(Location);
    const activation = TestBed.inject(TabActivationService);
    const replaceSpy = spyOn(location, 'replaceState');
    const setActiveSpy = spyOn(activation, 'setActive');

    c.onSlideChange({ target: { swiper: { activeIndex: 2 } } } as unknown as Event);

    expect(c.activeTab()).toBe('chat');
    expect(replaceSpy).toHaveBeenCalledWith('/tabs/chat');
    expect(setActiveSpy).toHaveBeenCalledWith('chat');
    expect(scrollSvc.setHoldFull).toHaveBeenCalledWith(true);
  });

  it('selectTab moves the swiper to the tab index', () => {
    const c = build().componentInstance;
    c.ngOnInit();
    const slideTo = jasmine.createSpy('slideTo');
    (c as any).swiperRef = { nativeElement: { swiper: { slideTo, allowTouchMove: true } } };

    c.selectTab('tab3');
    expect(slideTo).toHaveBeenCalledWith(3, undefined);
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
