import { Subject } from 'rxjs';

/**
 * Where Ask FRED keeps chat on the device, and how it is wiped.
 *
 * Chat is private to one account. Everything here is stored under a key that
 * carries the user id, and all of it is removed when the account on this
 * device changes (sign-out, or a different user signing in). A phone shared
 * by two people never shows one person's chat to the other.
 */
export class ChatStorageUtils {
  /** Old shared key (no user id). Nobody can say whose chat is in it, so it is only ever deleted. */
  static readonly LEGACY_HISTORY_KEY = 'fred_chat_history';

  private static readonly HISTORY_PREFIX = 'fred_chat_history_';
  private static readonly USED_SUGGESTIONS_PREFIX = 'fred_used_suggestions_';
  private static readonly DAILY_SUGGESTIONS_PREFIX = 'fred_daily_suggestions_';

  private static readonly clearedSubject = new Subject<void>();
  /** Fires each time stored chat is wiped, so a chat screen still in memory can blank itself too. */
  static readonly cleared$ = ChatStorageUtils.clearedSubject.asObservable();

  /** The signed-in user's id, or null when nobody is signed in on this device. */
  static currentUserId(): string | null {
    try {
      return localStorage.getItem('userId') || null;
    } catch {
      return null;
    }
  }

  static historyKey(userId: string): string {
    return `${ChatStorageUtils.HISTORY_PREFIX}${userId}`;
  }

  static usedSuggestionsKey(day: string, userId: string | null): string {
    return `${ChatStorageUtils.USED_SUGGESTIONS_PREFIX}${day}_${userId ?? 'anon'}`;
  }

  static dailySuggestionsKey(day: string, userId: string | null): string {
    return `${ChatStorageUtils.DAILY_SUGGESTIONS_PREFIX}${day}_${userId ?? 'anon'}`;
  }

  /** Delete the old shared history. Safe to call any time. */
  static removeLegacyHistory(): void {
    try {
      localStorage.removeItem(ChatStorageUtils.LEGACY_HISTORY_KEY);
    } catch { /* storage unavailable: nothing to remove */ }
  }

  /**
   * Remove every piece of chat stored on this device, for every account:
   * saved conversations, tapped suggestions and the cached suggestion cards.
   */
  static clearAll(): void {
    try {
      const doomed: string[] = [];
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (!key) continue;
        if (key === ChatStorageUtils.LEGACY_HISTORY_KEY
          || key.startsWith(ChatStorageUtils.HISTORY_PREFIX)
          || key.startsWith(ChatStorageUtils.USED_SUGGESTIONS_PREFIX)
          || key.startsWith(ChatStorageUtils.DAILY_SUGGESTIONS_PREFIX)) {
          doomed.push(key);
        }
      }
      doomed.forEach(key => localStorage.removeItem(key));
    } catch { /* storage unavailable: nothing to remove */ }
    ChatStorageUtils.clearedSubject.next();
  }
}
