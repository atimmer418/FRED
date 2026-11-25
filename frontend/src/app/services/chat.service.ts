import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, Observer } from 'rxjs';
import { environment } from '../../environments/environment';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { ChatStorageUtils } from '../utils/chat-storage.utils';

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  sessionId?: string;
  failed?: boolean;
}

export interface ChatSession {
  id: string;
  title: string;
  lastModified: number;
  messages: ChatMessage[];
  /** User id of the account this conversation belongs to. A session is only ever saved for its owner. */
  ownerId?: string;
}

@Injectable({
  providedIn: 'root'
})
export class ChatService {
  private apiUrl = `${environment.backendApiUrl}/chat`;
  private readonly SESSION_TIMEOUT_HOURS = 2;

  constructor(private http: HttpClient) {
    // Chat used to sit under one key shared by every account on the device.
    // Nobody can say whose it is, so it is deleted, never shown.
    ChatStorageUtils.removeLegacyHistory();
  }

  /** The signed-in user's id, or null when nobody is signed in. Chat on the device is kept per user. */
  currentUserId(): string | null {
    return ChatStorageUtils.currentUserId();
  }

  sendMessage(message: string, sessionId: string, generateTitle: boolean = false): Observable<{ reply: string, title?: string }> {
    const token = JwtTokenUtils.getValidJwtToken();
    const userId = localStorage.getItem('userId');

    let headers = new HttpHeaders({
      'Content-Type': 'application/json'
    });

    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }

    const body = {
      message: message,
      userId: userId ? parseInt(userId, 10) : null,
      sessionId: sessionId,
      generateTitle: generateTitle
    };

    return this.http.post<{ reply: string, title?: string }>(this.apiUrl, body, { headers });
  }

  streamChat(message: string, sessionId: string, generateTitle: boolean = false): Observable<{token?: string, done?: boolean, title?: string}> {
    const token = JwtTokenUtils.getValidJwtToken();
    const userId = localStorage.getItem('userId');

    const params = new URLSearchParams({ message, sessionId, generateTitle: generateTitle.toString() });
    if (userId) params.set('userId', userId);

    const headers: Record<string, string> = { 'Accept': 'text/event-stream' };
    if (token) headers['Authorization'] = `Bearer ${token}`;

    return new Observable((observer: Observer<{token?: string, done?: boolean, title?: string}>) => {
      let cancelled = false;

      fetch(`${this.apiUrl}/stream?${params.toString()}`, { headers, signal: undefined })
        .then(response => {
          if (!response.ok || !response.body) {
            observer.error(new Error(`HTTP ${response.status}`));
            return;
          }
          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          // SSE lines can straddle network chunks (common through the tunnel) —
          // buffer the trailing partial line instead of dropping mangled events.
          let buffer = '';

          const pump = (): Promise<void> => {
            if (cancelled) return Promise.resolve();
            return reader.read().then(({ done, value }) => {
              if (done) { observer.complete(); return; }
              buffer += decoder.decode(value, { stream: true });
              const lines = buffer.split('\n');
              buffer = lines.pop() ?? ''; // keep the (possibly partial) last line
              for (const line of lines) {
                if (!line.startsWith('data:')) continue;
                const data = line.slice(5).trim();
                if (!data) continue;
                try {
                  const parsed = JSON.parse(data);
                  observer.next(parsed);
                  if (parsed.done) { observer.complete(); return; }
                } catch { /* skip malformed */ }
              }
              return pump();
            });
          };

          pump().catch(err => { if (!cancelled) observer.error(err); });
        })
        .catch(err => { if (!cancelled) observer.error(err); });

      return () => { cancelled = true; };
    });
  }

  /** Phase B: execute a FRED-proposed action after the user's Confirm tap. */
  confirmAction(action: string, params: Record<string, unknown>, sessionId: string):
    Observable<{ success: boolean, message: string }> {
    const token = JwtTokenUtils.getValidJwtToken();
    let headers = new HttpHeaders({ 'Content-Type': 'application/json' });
    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }
    return this.http.post<{ success: boolean, message: string }>(
      `${this.apiUrl}/action`, { action, params, sessionId }, { headers });
  }

  getHistory(sessionId?: string, userId?: string | null): Observable<any[]> {
    const token = JwtTokenUtils.getValidJwtToken();
    let queryParams = '';

    if (sessionId) {
      queryParams = `?sessionId=${sessionId}`;
    } else if (userId) {
      queryParams = `?userId=${userId}`;
    }

    let headers = new HttpHeaders();
    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }

    return this.http.get<any[]>(`${this.apiUrl}/history${queryParams}`, { headers });
  }

  getDailySuggestions(): Observable<string[]> {
    const token = JwtTokenUtils.getValidJwtToken();

    let headers = new HttpHeaders();
    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }

    // No userId is sent: the backend personalizes from the signed-in user on the token.
    return this.http.get<string[]>(`${this.apiUrl}/suggestions`, { headers });
  }

  // History Management
  //
  // Saved conversations are private to one account. They live under a key that
  // carries the user id, every session records its owner, and all of it is
  // wiped when the account on the device changes (see ChatStorageUtils and
  // JwtTokenUtils). With nobody signed in, nothing is read and nothing is saved.

  /** The signed-in user's saved conversations, newest first. */
  getSessions(): ChatSession[] {
    const userId = this.currentUserId();
    if (!userId) return [];
    const data = localStorage.getItem(ChatStorageUtils.historyKey(userId));
    if (!data) return [];
    try {
      const sessions = JSON.parse(data);
      if (!Array.isArray(sessions)) return [];
      // Restore Date objects
      return sessions
        .filter((s: any) => s && s.ownerId === userId && Array.isArray(s.messages))
        .map((s: any) => ({
          ...s,
          messages: s.messages.map((m: any) => ({
            ...m,
            timestamp: new Date(m.timestamp)
          }))
        })).sort((a: ChatSession, b: ChatSession) => b.lastModified - a.lastModified);
    } catch (e) {
      console.error('Error parsing chat history', e);
      return [];
    }
  }

  /**
   * Save a conversation for its owner. Refused when the session belongs to
   * someone other than the signed-in user: a reply that finishes after the
   * account changed must never land in the next user's history.
   */
  saveSession(session: ChatSession): void {
    const userId = this.currentUserId();
    if (!userId || session.ownerId !== userId) return;
    const sessions = this.getSessions();
    const index = sessions.findIndex(s => s.id === session.id);
    if (index >= 0) {
      sessions[index] = session;
    } else {
      sessions.unshift(session);
    }
    localStorage.setItem(ChatStorageUtils.historyKey(userId), JSON.stringify(sessions));
  }

  deleteSession(id: string): void {
    const userId = this.currentUserId();
    if (!userId) return;
    const sessions = this.getSessions().filter(s => s.id !== id);
    localStorage.setItem(ChatStorageUtils.historyKey(userId), JSON.stringify(sessions));
  }

  /** A new, empty conversation owned by the signed-in user. */
  createSession(): ChatSession {
    return {
      id: this.generateId(),
      title: 'New Chat',
      lastModified: Date.now(),
      messages: [],
      ownerId: this.currentUserId() ?? undefined
    };
  }

  getRecentSession(): ChatSession | null {
    const sessions = this.getSessions();
    if (sessions.length === 0) return null;

    const lastSession = sessions[0];
    const now = Date.now();
    const diffHours = (now - lastSession.lastModified) / (1000 * 60 * 60);

    if (diffHours < this.SESSION_TIMEOUT_HOURS) {
      return lastSession;
    }
    return null;
  }

  // Daily Suggestions Persistence (per day and per user, like the cards themselves)
  private usedSuggestionsKey(): string {
    const today = new Date().toISOString().split('T')[0];
    return ChatStorageUtils.usedSuggestionsKey(today, this.currentUserId());
  }

  markSuggestionAsUsed(question: string): void {
    const key = this.usedSuggestionsKey();
    const used = this.getUsedSuggestions();

    if (!used.includes(question)) {
      used.push(question);
      localStorage.setItem(key, JSON.stringify(used));
    }
  }

  getUsedSuggestions(): string[] {
    const data = localStorage.getItem(this.usedSuggestionsKey());
    if (!data) return [];
    try {
      const parsed = JSON.parse(data);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  // Daily Suggestions Cache
  // The chat page seeds its suggestion cards from this synchronously so they
  // are in the first paint instead of popping in after the /suggestions round
  // trip. Scoped per day (backend regenerates at midnight) and per user
  // (position 3 is personalized).

  private dailySuggestionsCacheKey(): string {
    const today = new Date().toISOString().split('T')[0];
    return ChatStorageUtils.dailySuggestionsKey(today, this.currentUserId());
  }

  getCachedDailySuggestions(): string[] | null {
    const data = localStorage.getItem(this.dailySuggestionsCacheKey());
    if (!data) return null;
    try {
      const parsed = JSON.parse(data);
      return Array.isArray(parsed) && parsed.length > 0 ? parsed : null;
    } catch {
      return null;
    }
  }

  cacheDailySuggestions(questions: string[]): void {
    // Never pin an empty set: a bad response must not blank the cards on the next launch
    if (!questions || questions.length === 0) return;
    localStorage.setItem(this.dailySuggestionsCacheKey(), JSON.stringify(questions));
  }

  private generateId(): string {
    return Date.now().toString(36) + Math.random().toString(36).substr(2);
  }
}
