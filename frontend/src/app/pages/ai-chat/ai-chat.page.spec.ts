import { TestBed } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { of, Subject, BehaviorSubject, throwError } from 'rxjs';
import { MenuController } from '@ionic/angular';
import { AiChatPage } from './ai-chat.page';
import { ChatService } from '../../services/chat.service';
import { FirstTimeTourService, TourStep } from '../../services/first-time-tour.service';
import { TabActivationService } from '../../services/tab-activation.service';
import { PinService } from '../../services/pin.service';
import { ToastService } from '../../services/toast.service';

function makeChatServiceMock() {
  return {
    getSessions: jasmine.createSpy('getSessions').and.returnValue([]),
    getRecentSession: jasmine.createSpy('getRecentSession').and.returnValue({ id: 's0', title: 'New Chat', lastModified: Date.now(), messages: [] }),
    getHistory: jasmine.createSpy('getHistory').and.returnValue(of([])),
    getDailySuggestions: jasmine.createSpy('getDailySuggestions').and.returnValue(of([])),
    getCachedDailySuggestions: jasmine.createSpy('getCachedDailySuggestions').and.returnValue(null),
    cacheDailySuggestions: jasmine.createSpy('cacheDailySuggestions'),
    getUsedSuggestions: jasmine.createSpy('getUsedSuggestions').and.returnValue([]),
    createSession: jasmine.createSpy('createSession').and.returnValue({ id: 's1', messages: [] }),
    saveSession: jasmine.createSpy('saveSession'),
    markSuggestionAsUsed: jasmine.createSpy('markSuggestionAsUsed'),
  };
}

function makeTourMock() {
  const step$ = new BehaviorSubject<TourStep | null>(null);
  const mock = {
    step$: step$.asObservable(),
    currentStep: null as TourStep | null,
    isTourActive: () => false,
    getCurrentStep: () => 0,
    storyChipTapped: jasmine.createSpy('storyChipTapped'),
    completeTour: jasmine.createSpy('completeTour'),
    setStep(step: TourStep | null) { mock.currentStep = step; step$.next(step); },
  };
  return mock;
}

function configure(chat: ReturnType<typeof makeChatServiceMock>, tour: ReturnType<typeof makeTourMock> = makeTourMock()) {
  TestBed.configureTestingModule({
    imports: [AiChatPage, RouterTestingModule, HttpClientTestingModule],
    providers: [{
      provide: MenuController,
      useValue: {
        close: jasmine.createSpy('close'),
        enable: jasmine.createSpy('enable'),
        swipeGesture: jasmine.createSpy('swipeGesture'),
      },
    }],
  })
    .overrideProvider(ChatService, { useValue: chat })
    // Action-card deps (PIN sheet, toasts) pull ModalController/HttpClient; unused on these paths
    .overrideProvider(PinService, { useValue: {} })
    .overrideProvider(ToastService, { useValue: {} })
    // Shaped for both the legacy numeric-step API and tour v2 (step$/currentStep/storyChipTapped)
    .overrideProvider(FirstTimeTourService, { useValue: tour });
}

describe('AiChatPage activation gating', () => {
  let chat: ReturnType<typeof makeChatServiceMock>;

  beforeEach(() => {
    chat = makeChatServiceMock();
    configure(chat);
  });

  it('does not load chat data until first activation, then gates re-loads', () => {
    const fixture = TestBed.createComponent(AiChatPage);
    const c = fixture.componentInstance;
    const menu = TestBed.inject(MenuController) as any;
    // NOTE: no fixture.detectChanges() — keeps ngOnInit (keyboard listeners) from running.

    expect(chat.getDailySuggestions).not.toHaveBeenCalled();
    expect(c.isActive).toBeFalse();

    c.onChatActiveChange(true);
    expect(c.isActive).toBeTrue();
    expect(chat.getSessions).toHaveBeenCalled();
    expect(chat.getDailySuggestions).toHaveBeenCalledTimes(1);
    expect(menu.enable).toHaveBeenCalledWith(true, 'chat-menu');

    c.onChatActiveChange(false);
    expect(c.isActive).toBeFalse();
    expect(menu.close).toHaveBeenCalledWith('chat-menu');

    c.onChatActiveChange(true);
    expect(chat.getDailySuggestions).toHaveBeenCalledTimes(1); // gated — not reloaded
  });
});

/**
 * Suggestion cards must be present in the FIRST paint of the page, not pop in
 * after the /suggestions round trip. The page seeds from ChatService's
 * per-day cache synchronously and refreshes in the background.
 */
describe('AiChatPage suggestion cards first-paint', () => {
  let chat: ReturnType<typeof makeChatServiceMock>;

  beforeEach(() => {
    chat = makeChatServiceMock();
    configure(chat);
  });

  it('seeds cards from the cache synchronously, before any fetch resolves', () => {
    chat.getCachedDailySuggestions.and.returnValue(['a', 'b', 'c']);
    chat.getDailySuggestions.and.returnValue(new Subject<string[]>()); // never resolves
    const c = TestBed.createComponent(AiChatPage).componentInstance;

    c.primeSuggestions();

    expect(c.dailySuggestions).toEqual(['a', 'b', 'c']);
  });

  it('filters already-used questions out of the cached seed', () => {
    chat.getCachedDailySuggestions.and.returnValue(['a', 'b', 'c']);
    chat.getUsedSuggestions.and.returnValue(['b']);
    const c = TestBed.createComponent(AiChatPage).componentInstance;

    c.primeSuggestions();

    expect(c.dailySuggestions).toEqual(['a', 'c']);
  });

  it('fetches immediately when the cache is cold instead of waiting for activation', () => {
    chat.getCachedDailySuggestions.and.returnValue(null);
    const c = TestBed.createComponent(AiChatPage).componentInstance;

    c.primeSuggestions();

    expect(chat.getDailySuggestions).toHaveBeenCalledTimes(1);
  });

  it('does not fetch on prime when the cache is warm (activation/preload refreshes later)', () => {
    chat.getCachedDailySuggestions.and.returnValue(['a', 'b', 'c']);
    const c = TestBed.createComponent(AiChatPage).componentInstance;

    c.primeSuggestions();

    expect(chat.getDailySuggestions).not.toHaveBeenCalled();
  });

  it('writes a fetched list to the cache', () => {
    chat.getDailySuggestions.and.returnValue(of(['x', 'y', 'z']));
    const c = TestBed.createComponent(AiChatPage).componentInstance;

    c.loadDailySuggestions();

    expect(chat.cacheDailySuggestions).toHaveBeenCalledWith(['x', 'y', 'z']);
    expect(c.dailySuggestions).toEqual(['x', 'y', 'z']);
  });

  it('keeps the same array instance when the fetched list matches what is shown', () => {
    chat.getCachedDailySuggestions.and.returnValue(['a', 'b', 'c']);
    chat.getDailySuggestions.and.returnValue(of(['a', 'b', 'c']));
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.primeSuggestions();
    const shown = c.dailySuggestions;

    c.loadDailySuggestions();

    expect(c.dailySuggestions).toBe(shown);
  });

  it('keeps the cached cards when the refresh fetch fails', () => {
    chat.getCachedDailySuggestions.and.returnValue(['a', 'b', 'c']);
    chat.getDailySuggestions.and.returnValue(throwError(() => new Error('boom')));
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.primeSuggestions();

    c.loadDailySuggestions();

    expect(c.dailySuggestions).toEqual(['a', 'b', 'c']);
  });

  it('startNewChat re-filters locally instead of refetching', () => {
    chat.getDailySuggestions.and.returnValue(of(['a', 'b', 'c']));
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.onChatActiveChange(true); // one fetch here (history sync -> startNewChat must not add another)
    expect(chat.getDailySuggestions).toHaveBeenCalledTimes(1);

    chat.getUsedSuggestions.and.returnValue(['a']);
    c.currentSession = { id: 's9', title: 'Old', lastModified: 0, messages: [] };
    c.startNewChat();

    expect(chat.getDailySuggestions).toHaveBeenCalledTimes(1);
    expect(c.dailySuggestions).toEqual(['b', 'c']);
  });
});

/**
 * Final tour step: the "What's your story FRED?" card must be on screen and
 * tappable no matter what the backend returned, and tapping it reports to
 * the tour.
 */
describe('AiChatPage first-time tour story chip', () => {
  let chat: ReturnType<typeof makeChatServiceMock>;
  let tour: ReturnType<typeof makeTourMock>;

  beforeEach(() => {
    chat = makeChatServiceMock();
    tour = makeTourMock();
    configure(chat, tour);
  });

  it('entering chatStory puts the story chip first, ahead of the cached cards', () => {
    chat.getCachedDailySuggestions.and.returnValue(['How do ETFs work?', 'What is a Roth IRA?']);
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.primeSuggestions();
    expect(c.dailySuggestions).not.toContain(c.FRED_STORY_TRIGGER);

    tour.setStep('chatStory');

    expect(c.dailySuggestions[0]).toBe(c.FRED_STORY_TRIGGER);
    expect(c.dailySuggestions.length).toBe(3);
  });

  it('a late fetch cannot remove the story chip while on chatStory', () => {
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    tour.setStep('chatStory');
    chat.getDailySuggestions.and.returnValue(of(['Only one question']));

    c.loadDailySuggestions();

    expect(c.dailySuggestions).toEqual([c.FRED_STORY_TRIGGER, 'Only one question']);
  });

  it('a failed fetch still leaves the story chip while on chatStory', () => {
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    tour.setStep('chatStory');
    chat.getDailySuggestions.and.returnValue(throwError(() => new Error('boom')));

    c.loadDailySuggestions();

    expect(c.dailySuggestions).toEqual([c.FRED_STORY_TRIGGER]);
  });

  it('does not inject the story chip when the tour is not on chatStory', () => {
    chat.getCachedDailySuggestions.and.returnValue(['a', 'b']);
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.primeSuggestions();
    expect(c.dailySuggestions).toEqual(['a', 'b']);
  });

  it('entering chatStory with an existing conversation resets to an empty chat first', () => {
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.messages = [{ role: 'user', content: 'hi', timestamp: new Date() } as any];

    tour.setStep('chatStory');

    expect(c.messages.length).toBe(0);
    expect(chat.createSession).toHaveBeenCalled();
    expect(c.dailySuggestions[0]).toBe(c.FRED_STORY_TRIGGER);
  });

  it('tapping the story chip on chatStory notifies the tour and still sends', () => {
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    spyOn(c, 'sendMessage');
    tour.setStep('chatStory');

    c.selectSuggestion(c.FRED_STORY_TRIGGER);

    expect(tour.storyChipTapped).toHaveBeenCalled();
    expect(chat.markSuggestionAsUsed).toHaveBeenCalledWith(c.FRED_STORY_TRIGGER);
    expect(c.sendMessage).toHaveBeenCalled();
  });

  it('tapping the story chip off the tour does not notify the tour', () => {
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    spyOn(c, 'sendMessage');

    c.selectSuggestion(c.FRED_STORY_TRIGGER);

    expect(tour.storyChipTapped).not.toHaveBeenCalled();
  });
});
