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
import { ChatService } from './chat.service';

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
