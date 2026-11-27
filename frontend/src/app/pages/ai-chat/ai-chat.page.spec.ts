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
import { ChatStorageUtils } from '../../utils/chat-storage.utils';

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
    currentUserId: jasmine.createSpy('currentUserId').and.returnValue('1'),
    streamChat: jasmine.createSpy('streamChat').and.returnValue(of({ done: true })),
    confirmAction: jasmine.createSpy('confirmAction').and.returnValue(of({ success: true, message: 'ok' })),
  };
}

function makeTourMock() {
  const step$ = new BehaviorSubject<TourStep | null>(null);
  const mock = {
    step$: step$.asObservable(),
    currentStep: null as TourStep | null,
    storyChipTapped: jasmine.createSpy('storyChipTapped'),
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
    .overrideProvider(PinService, { useValue: { hasPin: () => Promise.resolve(false) } })
    .overrideProvider(ToastService, { useValue: {} })
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

/**
 * Action outcome records (the ✅/⚠️ messages the backend persists after an
 * executed action, and the local 🚫 cancel note) must render INSIDE the action
 * card, never as their own chat bubble — and on reload they must rehydrate the
 * card's resolved state so a resolved proposal can never come back tappable.
 */
describe('AiChatPage action outcome records', () => {
  let chat: ReturnType<typeof makeChatServiceMock>;

  beforeEach(() => {
    chat = makeChatServiceMock();
    configure(chat);
  });

  const proposalContent = 'Here you go:\n```action\n'
    + '{"action": "pause_investing", "params": {}, "summary": "Pauses automatic investing"}\n```';

  function pageWithMessages(contents: Array<{ role: 'user' | 'assistant'; content: string }>) {
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.messages = contents.map(m => ({ ...m, timestamp: new Date() }));
    return c;
  }

  it('hides an outcome record that follows an action proposal', () => {
    const c = pageWithMessages([
      { role: 'user', content: 'pause my investing' },
      { role: 'assistant', content: proposalContent },
      { role: 'assistant', content: '✅ Done. Automatic investing is paused.' },
    ]);

    expect(c.isHiddenActionOutcome(2)).toBeTrue();
    expect(c.isHiddenActionOutcome(1)).toBeFalse(); // the proposal itself renders
    expect(c.isHiddenActionOutcome(0)).toBeFalse();
  });

  it('does not hide a ✅-looking message with no action proposal before it', () => {
    const c = pageWithMessages([
      { role: 'user', content: 'hi' },
      { role: 'assistant', content: '✅ Done. Automatic investing is paused.' },
    ]);

    expect(c.isHiddenActionOutcome(1)).toBeFalse();
  });

  it('rehydrates the card state from a persisted outcome record on reload', () => {
    const c = pageWithMessages([
      { role: 'assistant', content: proposalContent },
      { role: 'assistant', content: '✅ Done. Automatic investing is paused.' },
    ]);

    const seg = c.segments(c.messages[0]).find(s => s.kind === 'action') as any;
    expect(seg.state).toBe('done');
    expect(seg.resultMessage).toBe('Done. Automatic investing is paused.');
    // Resolved + not latest → the reloaded card can never execute again
    expect(c.isActionable(c.messages[0])).toBeFalse();
  });

  it('rehydrates a cancelled card from the local 🚫 note', () => {
    const c = pageWithMessages([
      { role: 'assistant', content: proposalContent },
      { role: 'assistant', content: '🚫 Cancelled. Nothing was changed.' },
    ]);

    const seg = c.segments(c.messages[0]).find(s => s.kind === 'action') as any;
    expect(seg.state).toBe('cancelled');
  });

  it('leaves a card idle when no outcome record follows it', () => {
    const c = pageWithMessages([
      { role: 'assistant', content: proposalContent },
    ]);

    const seg = c.segments(c.messages[0]).find(s => s.kind === 'action') as any;
    expect(seg.state).toBe('idle');
  });
});

/**
 * Chat is private to one account. This page lives inside the tabs pager and
 * can stay in memory across a sign-out, so it must drop what it holds the
 * moment the account on the device changes, and ignore anything that arrives
 * late for the previous account.
 */
describe('AiChatPage on a shared phone', () => {
  const SECRET = 'A-private: my salary is 91234';
  let chat: ReturnType<typeof makeChatServiceMock>;

  function sessionOf(owner: string, text: string) {
    return {
      id: 'sess-' + owner, title: 'Chat of ' + owner, lastModified: Date.now(), ownerId: owner,
      messages: [{ role: 'user', content: text, timestamp: new Date() }],
    };
  }

  /** User A (id 1) opens chat and has a conversation on screen. */
  function pageWithUserAChat(): AiChatPage {
    const a = sessionOf('1', SECRET);
    chat.getSessions.and.returnValue([a]);
    chat.getRecentSession.and.returnValue(a);
    chat.getDailySuggestions.and.returnValue(of(['A personal card']));
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.onChatActiveChange(true);
    c.newMessage = 'half typed by A';
    expect(c.messages.map(m => m.content)).toEqual([SECRET]);
    expect(c.sessions.length).toBe(1);
    return c;
  }

  function everythingOnScreen(c: AiChatPage): string {
    return JSON.stringify([c.messages, c.sessions, c.currentSession, c.newMessage, c.dailySuggestions]);
  }

  /** B (id 2) is now the account on the device and has no chat of their own. */
  function userBTakesOver() {
    chat.currentUserId.and.returnValue('2');
    chat.getSessions.and.returnValue([]);
    chat.getRecentSession.and.returnValue(null);
    chat.getHistory.and.returnValue(of([]));
    chat.getDailySuggestions.and.returnValue(of(['B card']));
    chat.createSession.and.returnValue({ id: 'b-new', title: 'New Chat', lastModified: Date.now(), messages: [], ownerId: '2' });
  }

  beforeEach(() => {
    chat = makeChatServiceMock();
    configure(chat);
  });

  it('signing out blanks the chat held by the page', () => {
    const c = pageWithUserAChat();

    chat.currentUserId.and.returnValue(null);
    ChatStorageUtils.clearAll(); // what sign-out does

    expect(c.messages).toEqual([]);
    expect(c.sessions).toEqual([]);
    expect(c.currentSession).toBeNull();
    expect(c.newMessage).toBe('');
    expect(c.dailySuggestions).toEqual([]);
    expect(everythingOnScreen(c)).not.toContain('91234');
  });

  it('user B opening chat after A signed out sees only B\'s own chat', () => {
    const c = pageWithUserAChat();
    chat.currentUserId.and.returnValue(null);
    ChatStorageUtils.clearAll();

    userBTakesOver();
    c.onChatActiveChange(true);

    expect(chat.getHistory.calls.mostRecent().args).toEqual([undefined, '2']);
    expect(c.messages).toEqual([]);
    expect(c.sessions).toEqual([]);
    expect(c.currentSession?.id).toBe('b-new');
    expect(c.dailySuggestions).toEqual(['B card']);
    expect(everythingOnScreen(c)).not.toContain('91234');
  });

  it('user B signing in without A signing out still gets a blank chat', () => {
    // No wipe event reaches the page: the account id alone changed.
    const c = pageWithUserAChat();
    c.onChatActiveChange(false);

    userBTakesOver();
    c.onChatActiveChange(true);

    expect(c.messages).toEqual([]);
    expect(c.sessions).toEqual([]);
    expect(c.currentSession?.id).toBe('b-new');
    expect(c.newMessage).toBe('');
    expect(everythingOnScreen(c)).not.toContain('91234');
  });

  it('the same user coming back to chat keeps their conversation', () => {
    const c = pageWithUserAChat();
    c.onChatActiveChange(false);
    c.onChatActiveChange(true);

    expect(c.messages.map(m => m.content)).toEqual([SECRET]);
    expect(c.newMessage).toBe('half typed by A');
  });

  it('A\'s history arriving after B signed in is thrown away', () => {
    const lateHistory = new Subject<any[]>();
    chat.getSessions.and.returnValue([]);
    chat.getHistory.and.returnValue(lateHistory);
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.onChatActiveChange(true); // A's history request is now in flight

    chat.currentUserId.and.returnValue('2');
    lateHistory.next([{ role: 'user', content: SECRET, createdAt: new Date().toISOString(), sessionId: 'sess-1' }]);

    expect(everythingOnScreen(c)).not.toContain('91234');
    expect(chat.saveSession).not.toHaveBeenCalled();
  });

  it('A\'s history request is cancelled when A signs out', () => {
    const lateHistory = new Subject<any[]>();
    chat.getSessions.and.returnValue([]);
    chat.getHistory.and.returnValue(lateHistory);
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.onChatActiveChange(true);
    expect(lateHistory.observed).toBeTrue();

    chat.currentUserId.and.returnValue(null);
    ChatStorageUtils.clearAll();

    expect(lateHistory.observed).toBeFalse();
    expect(c.isLoading).toBeFalse();
  });

  it('A\'s reply still streaming when the account changes is dropped and never saved', () => {
    const c = pageWithUserAChat();
    const stream = new Subject<{ token?: string; done?: boolean; title?: string }>();
    chat.streamChat.and.returnValue(stream);
    c.newMessage = 'what about my 91234 salary';
    c.sendMessage();
    stream.next({ token: 'Your 91234 ' });
    chat.saveSession.calls.reset();

    chat.currentUserId.and.returnValue(null);
    ChatStorageUtils.clearAll(); // A signs out mid-reply
    expect(stream.observed).toBeFalse();

    userBTakesOver();
    c.onChatActiveChange(true);
    stream.next({ token: 'salary is fine' });
    stream.next({ done: true, title: 'Salary 91234' });
    stream.complete();

    expect(chat.saveSession).not.toHaveBeenCalled();
    expect(everythingOnScreen(c)).not.toContain('91234');
  });

  it('a streamed reply is dropped when the account changed with no wipe event', () => {
    const c = pageWithUserAChat();
    const stream = new Subject<{ token?: string; done?: boolean; title?: string }>();
    chat.streamChat.and.returnValue(stream);
    c.newMessage = 'question';
    c.sendMessage();
    chat.saveSession.calls.reset();
    const before = c.messages[c.messages.length - 1].content;

    chat.currentUserId.and.returnValue('2');
    stream.next({ token: 'for A only' });
    stream.next({ done: true, title: 'A title' });
    stream.complete();

    expect(c.messages[c.messages.length - 1].content).toBe(before);
    expect(chat.saveSession).not.toHaveBeenCalled();
  });

  it('A\'s suggestion cards arriving after B signed in are not cached or shown', () => {
    const lateCards = new Subject<string[]>();
    chat.getDailySuggestions.and.returnValue(lateCards);
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.loadDailySuggestions();

    chat.currentUserId.and.returnValue('2');
    lateCards.next(['How is your 91234 salary doing?']);

    expect(chat.cacheDailySuggestions).not.toHaveBeenCalled();
    expect(c.dailySuggestions).toEqual([]);
  });

  it('a conversation resumed from the server is saved for the signed-in user', () => {
    chat.getSessions.and.returnValue([]);
    chat.getHistory.and.returnValue(of([
      { role: 'user', content: 'hello', createdAt: new Date().toISOString(), sessionId: 'srv-1' },
    ]));
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.onChatActiveChange(true);

    expect(chat.saveSession).toHaveBeenCalledTimes(1);
    expect(chat.saveSession.calls.mostRecent().args[0].ownerId).toBe('1');
    expect(c.currentSession?.id).toBe('srv-1');
  });

  it('the first suggestions request is not restarted when chat loads', () => {
    const slow = new Subject<string[]>();
    chat.getDailySuggestions.and.returnValue(slow);
    const c = TestBed.createComponent(AiChatPage).componentInstance;
    c.primeSuggestions(); // cold cache: fetch starts
    c.onChatActiveChange(true); // first activation while it is still in flight

    expect(chat.getDailySuggestions).toHaveBeenCalledTimes(1);
    slow.next(['a', 'b']);
    expect(c.dailySuggestions).toEqual(['a', 'b']);
  });

  it('the outcome of a change A confirmed is not added to B\'s chat', async () => {
    const c = pageWithUserAChat();
    const result = new Subject<{ success: boolean; message: string }>();
    chat.confirmAction.and.returnValue(result);
    const msg = c.messages[c.messages.length - 1];
    const seg: any = { kind: 'action', state: 'idle', proposal: { action: 'x', params: {}, summary: 's' } };
    await c.confirmAction(msg, seg);
    expect(chat.confirmAction).toHaveBeenCalled();

    userBTakesOver();
    c.onChatActiveChange(false);
    c.onChatActiveChange(true);
    chat.saveSession.calls.reset();
    result.next({ success: true, message: 'Changed A\'s 91234 plan' });

    expect(c.actionInFlight).toBeFalse();
    expect(c.messages).toEqual([]);
    expect(chat.saveSession).not.toHaveBeenCalled();
  });

  it('stops listening for wipes once the page is destroyed', () => {
    const c = pageWithUserAChat();
    c.ngOnDestroy();
    expect(() => ChatStorageUtils.clearAll()).not.toThrow();
    expect(c.messages.length).toBe(1); // untouched: the page is gone
  });
});

describe('AiChatPage: what FRED says when a message cannot be sent', () => {
  const failure = (message: string, retryAfterSeconds?: number) => Object.assign(new Error(message), { retryAfterSeconds });

  it('too many messages in a minute: wait a minute', () => {
    expect(AiChatPage.streamErrorCopy(failure('HTTP 429', 60))).toContain('Give it a minute');
    expect(AiChatPage.streamErrorCopy(failure('HTTP 429'))).toContain('Give it a minute');
  });

  it('the day\'s allowance is used up: come back tomorrow, not "in a minute"', () => {
    const copy = AiChatPage.streamErrorCopy(failure('HTTP 429', 86400));
    expect(copy).toContain('today');
    expect(copy).not.toContain('minute');
  });

  it('a message that is too long says so', () => {
    expect(AiChatPage.streamErrorCopy(failure('HTTP 413'))).toContain('too long');
  });

  it('anything else is the usual connection message', () => {
    expect(AiChatPage.streamErrorCopy(failure('HTTP 500'))).toContain('trouble connecting');
    expect(AiChatPage.streamErrorCopy(null)).toContain('trouble connecting');
  });
});
