/**
 * ChatService daily-suggestion cache.
 *
 * The chat page seeds its suggestion cards from this cache synchronously so
 * they are on screen in the first paint instead of popping in after the
 * /suggestions round trip. The cache is scoped per calendar day AND per user:
 * the backend regenerates the set at midnight, and position 3 is personalized.
 */
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ChatService, ChatSession } from './chat.service';
import { JwtTokenUtils } from '../utils/jwt-token.utils';

describe('ChatService daily suggestions cache', () => {
  let service: ChatService;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('userId', '42');
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ChatService],
    });
    service = TestBed.inject(ChatService);
  });

  afterEach(() => localStorage.clear());

  it('returns null when nothing has been cached today', () => {
    expect(service.getCachedDailySuggestions()).toBeNull();
  });

  it('round-trips the cached list for the same user on the same day', () => {
    service.cacheDailySuggestions(['a', 'b', 'c']);
    expect(service.getCachedDailySuggestions()).toEqual(['a', 'b', 'c']);
  });

  it('does not serve one user\'s cached suggestions to another user', () => {
    service.cacheDailySuggestions(['a', 'b', 'c']);
    localStorage.setItem('userId', '99');
    expect(service.getCachedDailySuggestions()).toBeNull();
  });

  it('ignores an empty list so a bad response never pins empty cards', () => {
    service.cacheDailySuggestions(['a', 'b', 'c']);
    service.cacheDailySuggestions([]);
    expect(service.getCachedDailySuggestions()).toEqual(['a', 'b', 'c']);
  });

  it('returns null when the stored value is corrupt', () => {
    service.cacheDailySuggestions(['a']);
    const key = Object.keys(localStorage).find(k => k.startsWith('fred_daily_suggestions_'))!;
    localStorage.setItem(key, '{not json');
    expect(service.getCachedDailySuggestions()).toBeNull();
  });
});

/**
 * Chat kept on the device is private to one account.
 *
 * Two people can use the same phone. Whatever user A typed to FRED must be
 * gone from the device when A signs out, and must never show up for user B,
 * however B got signed in.
 */
describe('ChatService on a shared phone', () => {
  const SECRET = 'A-private: my salary is 91234 and I owe 40000';
  let service: ChatService;

  function tokenFor(email: string): string {
    const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
    const body = btoa(JSON.stringify({ sub: email, exp: Math.floor(Date.now() / 1000) + 3600 }));
    return `${header}.${body}.sig`;
  }

  function signIn(userId: number, email: string): void {
    JwtTokenUtils.storeJwtToken(tokenFor(email), userId, email);
  }

  /** Everything the device holds, as one string, to prove a text is really gone. */
  function everythingStored(): string {
    return Object.keys(localStorage).map(k => `${k}=${localStorage.getItem(k)}`).join('\n');
  }

  function userAChats(): ChatSession {
    const session = service.createSession();
    session.title = 'A talks money';
    session.messages = [{ role: 'user', content: SECRET, timestamp: new Date() }];
    service.saveSession(session);
    return session;
  }

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ChatService],
    });
    service = TestBed.inject(ChatService);
  });

  afterEach(() => localStorage.clear());

  it('keeps a user\'s own chat for them while they stay signed in', () => {
    signIn(1, 'a@example.com');
    userAChats();

    expect(service.getSessions().length).toBe(1);
    expect(service.getSessions()[0].messages[0].content).toBe(SECRET);
    expect(service.getRecentSession()?.title).toBe('A talks money');
  });

  it('signing out removes the chat from the device', () => {
    signIn(1, 'a@example.com');
    userAChats();

    JwtTokenUtils.clearJwtData();

    expect(everythingStored()).not.toContain(SECRET);
    expect(service.getSessions()).toEqual([]);
    expect(service.getRecentSession()).toBeNull();
  });

  it('user B never sees user A\'s chat after A signs out', () => {
    signIn(1, 'a@example.com');
    userAChats();
    JwtTokenUtils.clearJwtData();

    signIn(2, 'b@example.com');

    expect(service.getSessions()).toEqual([]);
    expect(service.getRecentSession()).toBeNull();
    expect(everythingStored()).not.toContain(SECRET);
  });

  it('user B never sees user A\'s chat when B signs in without A signing out', () => {
    // A's session ran out: the token is gone but the app still remembers A.
    signIn(1, 'a@example.com');
    userAChats();
    localStorage.removeItem('jwtToken');
    localStorage.removeItem('jwtExpiration');

    signIn(2, 'b@example.com');

    expect(service.getSessions()).toEqual([]);
    expect(service.getRecentSession()).toBeNull();
    expect(everythingStored()).not.toContain(SECRET);
  });

  it('the same user signing in again keeps their chat', () => {
    signIn(1, 'a@example.com');
    userAChats();
    localStorage.removeItem('jwtToken');
    localStorage.removeItem('jwtExpiration');

    signIn(1, 'a@example.com');

    expect(service.getSessions().length).toBe(1);
  });

  it('a refreshed token for the same user keeps their chat', () => {
    signIn(1, 'a@example.com');
    userAChats();

    // Token refresh and email change store a new token without a user id.
    JwtTokenUtils.storeJwtToken(tokenFor('a@example.com'), undefined, 'a@example.com');

    expect(service.getSessions().length).toBe(1);
  });

  it('never shows chat left in the old shared store, and deletes it when the service starts', () => {
    // A signed-in user updates the app: no sign-in or sign-out happens, so only
    // the service starting up can remove the old shared store.
    const old = [{ id: 'old1', title: 'Old chat', lastModified: Date.now(),
      messages: [{ role: 'user', content: SECRET, timestamp: new Date().toISOString() }] }];
    localStorage.setItem('userId', '2');
    localStorage.setItem('fred_chat_history', JSON.stringify(old));

    const fresh = new ChatService(null as any);

    expect(localStorage.getItem('fred_chat_history')).toBeNull();
    expect(fresh.getSessions()).toEqual([]);
    expect(everythingStored()).not.toContain(SECRET);
  });

  it('one user\'s saved chat is never returned for another user id', () => {
    signIn(1, 'a@example.com');
    userAChats();
    // Worst case: the user id changes underneath with no wipe at all.
    localStorage.setItem('userId', '2');

    expect(service.getSessions()).toEqual([]);
    expect(service.getRecentSession()).toBeNull();

    // Even a copy of A's list planted under B's key is not shown: each session names its owner.
    localStorage.setItem('fred_chat_history_2', localStorage.getItem('fred_chat_history_1')!);
    expect(service.getSessions()).toEqual([]);
  });

  it('a reply that lands after the account changed is not saved for the new user', () => {
    signIn(1, 'a@example.com');
    const session = userAChats();
    JwtTokenUtils.clearJwtData();
    signIn(2, 'b@example.com');

    // A's stream finishes late and the page tries to save A's conversation.
    session.messages.push({ role: 'assistant', content: 'late reply about ' + SECRET, timestamp: new Date() });
    service.saveSession(session);

    expect(service.getSessions()).toEqual([]);
    expect(everythingStored()).not.toContain(SECRET);
  });

  it('nothing is saved when nobody is signed in', () => {
    const session = service.createSession();
    session.messages = [{ role: 'user', content: SECRET, timestamp: new Date() }];
    service.saveSession(session);

    expect(everythingStored()).not.toContain(SECRET);
    expect(service.getSessions()).toEqual([]);
  });

  it('deleting a chat only touches the signed-in user\'s list', () => {
    signIn(1, 'a@example.com');
    const session = userAChats();
    service.deleteSession(session.id);
    expect(service.getSessions()).toEqual([]);
    expect(everythingStored()).not.toContain(SECRET);
  });

  it('suggestions one user tapped are not hidden for, or shown to, the next user', () => {
    signIn(1, 'a@example.com');
    service.markSuggestionAsUsed('How is my 91234 salary doing?');
    service.cacheDailySuggestions(['How is my 91234 salary doing?']);
    expect(service.getUsedSuggestions()).toEqual(['How is my 91234 salary doing?']);

    localStorage.removeItem('jwtToken');
    signIn(2, 'b@example.com');

    expect(service.getUsedSuggestions()).toEqual([]);
    expect(service.getCachedDailySuggestions()).toBeNull();
    expect(everythingStored()).not.toContain('91234');
  });
});
