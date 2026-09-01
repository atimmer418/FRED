import { Component, OnInit, AfterViewInit, OnDestroy, ViewChild, ChangeDetectorRef, ElementRef, effect, HostBinding } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MenuController } from '@ionic/angular';
import {
  IonContent,
  IonHeader,
  IonToolbar,
  IonButtons,
  IonTitle,
  IonFooter,
  IonTextarea,
  IonButton,
  IonIcon,
  IonMenu,
  IonMenuToggle,
  IonMenuButton
} from '@ionic/angular/standalone';
import { ChatService, ChatMessage, ChatSession } from '../../services/chat.service';
import { FirstTimeTourService, TourStep } from '../../services/first-time-tour.service';
import { TabActivationService } from '../../services/tab-activation.service';
import { Subscription } from 'rxjs';
import { finalize } from 'rxjs/operators';
import { addIcons } from 'ionicons';
import { arrowUp, menuOutline, addOutline, refreshOutline, sparklesOutline, trendingUpOutline, chatbubbleEllipsesOutline, pencilOutline } from 'ionicons/icons';
import { TabBarScrollDirective } from '../../directives/tab-bar-scroll.directive';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { Keyboard } from '@capacitor/keyboard';
import { MarkdownPipe } from '../../pipes/markdown.pipe';
import { ChatChartComponent, ChatChartConfig } from './chat-chart.component';
import { ChatActionCardComponent, ChatActionProposal, ActionCardState } from './chat-action-card.component';

/** A rendered slice of an assistant message: markdown, chart, quick replies, or an action proposal */
export type MessageSegment =
  | { kind: 'md'; text: string }
  | { kind: 'chart'; config: ChatChartConfig }
  | { kind: 'suggestions'; options: string[] }
  | { kind: 'action'; proposal: ChatActionProposal; state: ActionCardState; resultMessage?: string };

/** Must mirror the backend allowlist (FredChatActionService.ALLOWED_ACTIONS) */
const KNOWN_ACTIONS = ['update_investment_amount', 'pause_investing', 'resume_investing'];

@Component({
  selector: 'app-ai-chat',
  templateUrl: './ai-chat.page.html',
  styleUrls: ['./ai-chat.page.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IonContent,
    IonHeader,
    IonToolbar,
    IonButtons,
    IonTitle,
    IonFooter,
    IonTextarea,
    IonButton,
    IonIcon,
    IonMenu,
    IonMenuToggle,
    IonMenuButton,
    TabBarScrollDirective,
    KeyboardAvoidDirective,
    MarkdownPipe,
    ChatChartComponent,
    ChatActionCardComponent
  ]
})
export class AiChatPage implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild(IonContent) content!: IonContent;

  readonly FRED_STORY_TRIGGER = "What's your story FRED?";

  private streamSubscription: Subscription | null = null;
  private tourSub: Subscription | null = null;

  /** The assistant message currently receiving streamed tokens (null when idle) */
  private activeStreamMsg: ChatMessage | null = null;

  @ViewChild('chatMenu', { read: ElementRef }) chatMenuRef?: ElementRef<HTMLElement>;
  @ViewChild(IonTextarea) composerTextarea?: IonTextarea;

  /** Keyboard height (px) while the native keyboard is up — lifts the composer (resize:'none'). */
  keyboardOffset = 0;

  /** #1 — while the keyboard is up, drop the chat slide's compositing layer (see
   *  :host / :host(.kb-up) in scss) so a composited ancestor can't break the
   *  WKWebView text caret. You never swipe tabs mid-type, so the layer isn't
   *  needed then. */
  @HostBinding('class.kb-up') get kbUp(): boolean {
    return this.keyboardOffset > 0;
  }
  private kbShow?: Promise<any>;
  private kbHide?: Promise<any>;
  private chatLoaded = false;

  messages: ChatMessage[] = [];
  sessions: ChatSession[] = [];
  currentSession: ChatSession | null = null;
  newMessage: string = '';
  isLoading: boolean = false;
  dailySuggestions: string[] = [];
  /** Unfiltered day's set (cache or last fetch); dailySuggestions = this minus used ones */
  private rawSuggestions: string[] = [];
  isActive: boolean = false;

  constructor(
    private chatService: ChatService,
    private cdr: ChangeDetectorRef,
    private menuCtrl: MenuController,
    private tourService: FirstTimeTourService,
    private tabActivation: TabActivationService
  ) {
    addIcons({ arrowUp, menuOutline, addOutline, refreshOutline, sparklesOutline, trendingUpOutline, chatbubbleEllipsesOutline, pencilOutline });

    // Drive isActive + first-load from the pager's active-slide signal.
    effect(() => {
      this.onChatActiveChange(this.tabActivation.activeTab() === 'chat');
    });

    this.tourSub = this.tourService.step$.subscribe(step => this.onTourStep(step));
  }

  get isTourOnStoryStep(): boolean {
    return this.tourService.currentStep === 'chatStory';
  }

  /** Empty state = no user message yet in this chat (hero + suggestion cards shown) */
  get isEmptyState(): boolean {
    return !this.messages.some(m => m.role === 'user');
  }

  get timeGreeting(): string {
    const hour = new Date().getHours();
    if (hour < 12) return 'Good morning.';
    if (hour < 17) return 'Good afternoon.';
    return 'Good evening.';
  }

  private readonly suggestionIcons = ['sparkles-outline', 'trending-up-outline', 'chatbubble-ellipses-outline'];

  suggestionIcon(index: number): string {
    return this.suggestionIcons[index % this.suggestionIcons.length];
  }

  /** Pig-themed thinking phrases — one picked at random per response */
  private readonly thinkingPhrases = [
    'Snorting...',
    'Rolling around in some mud...',
    'Rooting through the numbers...',
    'Counting coins in the trough...',
    'Sniffing out an answer...',
    'Filling the piggy bank...',
    'Oinking at the market...'
  ];
  thinkingPhrase = this.thinkingPhrases[0];

  private pickThinkingPhrase() {
    this.thinkingPhrase = this.thinkingPhrases[Math.floor(Math.random() * this.thinkingPhrases.length)];
  }

  ngOnInit() {
    // Suggestion cards must be in the FIRST paint: seed from the per-day cache
    // synchronously (fetch only if cold) so they never pop in on entry.
    this.primeSuggestions();

    // Chat data now loads on first activation (see onChatActiveChange), not on
    // mount — so eager pager mounting does not fire these on app entry.

    // Native-only (plugin never fires on web — same pattern as KeyboardAvoidDirective)
    this.kbShow = Keyboard.addListener('keyboardWillShow', info => {
      this.keyboardOffset = info.keyboardHeight;
      this.cdr.detectChanges();
      this.scrollToBottom();
    });
    this.kbHide = Keyboard.addListener('keyboardWillHide', () => {
      this.keyboardOffset = 0;
      this.cdr.detectChanges();
    });
  }

  ngOnDestroy() {
    this.kbShow?.then(h => h.remove());
    this.kbHide?.then(h => h.remove());
    this.streamSubscription?.unsubscribe();
    this.tourSub?.unsubscribe();
  }

  ngAfterViewInit() {
    // Portal the history drawer up to ion-app: chat is one of several slides in
    // the tabs pager, and staying inside the page means the drawer can never
    // stack above the floating tab bar. At app level the menu's backdrop dims
    // the ENTIRE app — tab bar included — and the panel slides over it.
    // Angular bindings and scoped styles travel with the node, and Angular
    // still removes it correctly on destroy.
    const ionApp = document.querySelector('ion-app');
    if (ionApp && this.chatMenuRef?.nativeElement) {
      ionApp.appendChild(this.chatMenuRef.nativeElement);
    }

    // Give time for async operations to complete and populate messages
    setTimeout(() => {
      if (this.messages.length > 0) {
        console.log('Scrolling to bottom from ngAfterViewInit, messages count:', this.messages.length);
        this.scrollToBottom();
      }
    }, 300);
  }

  /**
   * Runs when chat becomes/stops being the active pager slide (fired by the
   * activation effect). Loads chat data once on first activation, toggles
   * isActive, and scrolls to the latest message on activate.
   */
  onChatActiveChange(active: boolean) {
    this.isActive = active;
    if (!active) {
      // Close the drawer if it's open, but do NOT disable the menu. Re-enabling
      // it on the next entry was a real `disabled` change that re-ran Ionic's
      // menu updateState against #main-content — the .ion-page that holds the
      // composer — flashing the composer on EVERY entry to chat. Leaving it
      // enabled is safe: swipe-to-open is off and the hamburger that opens it
      // only exists on this page, so it can't be opened from other tabs.
      this.menuCtrl.close('chat-menu');
      return;
    }
    // Idempotent — these no-op once set (Stencil @Watch only fires on a real
    // change), so there is no per-entry menu reflow. Keeps the history drawer
    // opening ONLY via the hamburger, never an edge-swipe (which collided with
    // the pager's right-edge swipe).
    this.menuCtrl.enable(true, 'chat-menu');
    this.menuCtrl.swipeGesture(false, 'chat-menu');
    if (!this.chatLoaded) {
      this.chatLoaded = true;
      this.loadSessions();
      this.syncBackendHistory();
      this.loadDailySuggestions();
    }
    if (this.messages.length > 0) {
      setTimeout(() => this.scrollToBottom(), 300);
    }
  }

  /**
   * Seed the suggestion cards from today's cached set so they render with the
   * rest of the empty state, not after a network round trip. Cold cache (first
   * launch, new day, new user) fetches right away rather than waiting for the
   * 2.5s preload timer or first activation; a warm cache is refreshed later by
   * the normal preload/activation path.
   */
  primeSuggestions() {
    const cached = this.chatService.getCachedDailySuggestions();
    if (cached) {
      this.applySuggestions(cached);
    } else {
      this.loadDailySuggestions();
    }
  }

  loadDailySuggestions() {
    this.chatService.getDailySuggestions().subscribe({
      next: (questions) => {
        // Phase 3: Backend returns [LLM, Popular, Personalized/FRED]
        this.chatService.cacheDailySuggestions(questions);
        this.applySuggestions(questions);
      },
      error: (err) => {
        // Keep whatever is already showing (the cached seed); nothing to blank
        console.error('Failed to load daily questions', err);
      }
    });
  }

  /**
   * Filter out used questions from positions 1 & 2 (static until midnight);
   * position 3 (personalized) is always visible, backend handles rotation.
   * Keeps the existing array instance when nothing changed so *ngFor never
   * re-creates the cards for identical content.
   */
  private applySuggestions(questions: string[]) {
    this.rawSuggestions = questions;
    const used = this.chatService.getUsedSuggestions();
    let filtered = questions.filter(q => !used.includes(q));
    // Final tour step: the story chip must be on screen no matter what the
    // backend returned or what was already used. rawSuggestions stays clean.
    if (this.isTourOnStoryStep && !filtered.includes(this.FRED_STORY_TRIGGER)) {
      filtered = [this.FRED_STORY_TRIGGER, ...filtered];
    }
    const same = filtered.length === this.dailySuggestions.length
      && filtered.every((q, i) => q === this.dailySuggestions[i]);
    if (same) return;
    this.dailySuggestions = filtered;
    this.cdr.detectChanges();
  }

  /**
   * Final tour step: show the empty state with the story chip pinned. A live
   * conversation is only possible here when replaying the tour; reset it so
   * the cards are visible, then re-apply the day's set with the chip injected.
   */
  private onTourStep(step: TourStep | null) {
    if (step !== 'chatStory') return;
    if (!this.isEmptyState) {
      this.currentSession = this.chatService.createSession();
      this.messages = [];
    }
    this.applySuggestions(this.rawSuggestions);
    this.cdr.detectChanges();
  }

  selectSuggestion(question: string) {
    // Final tour gate: the story chip tap completes the tour; the send proceeds as normal.
    if (this.isTourOnStoryStep && question === this.FRED_STORY_TRIGGER) {
      this.tourService.storyChipTapped();
    }

    this.newMessage = question;

    // Mark as used to hide static questions on next load
    this.chatService.markSuggestionAsUsed(question);

    this.sendMessage();
  }

  syncBackendHistory() {
    const userId = localStorage.getItem('userId');
    const sessionId = this.currentSession?.id;

    if (!sessionId && !userId) {
      this.checkRecentSession();
      return;
    }

    this.isLoading = true;
    this.chatService.getHistory(sessionId, userId).subscribe({
      next: (history) => {
        this.isLoading = false;
        if (history && history.length > 0) {
          // Convert backend messages to frontend format
          const backendMessages: ChatMessage[] = history.map(m => ({
            role: m.role,
            content: m.content,
            timestamp: new Date(m.createdAt),
            sessionId: m.sessionId // Capture sessionId from messages
          }));

          const lastMsg = backendMessages[backendMessages.length - 1];
          const now = new Date();
          const diffHours = (now.getTime() - lastMsg.timestamp.getTime()) / (1000 * 60 * 60);

          // Use the sessionId from the backend history if we didn't have one
          const activeSessionId = sessionId || lastMsg.sessionId;

          if (diffHours < 2) {
            console.log('Found recent session, loading it:', activeSessionId);
            this.messages = backendMessages;

            // Update or Create session locally
            let session = this.sessions.find(s => s.id === activeSessionId);
            if (!session) {
              session = {
                id: activeSessionId!,
                title: 'Resumed Chat',
                lastModified: lastMsg.timestamp.getTime(),
                messages: this.messages
              };
            } else {
              session.messages = this.messages;
              session.lastModified = lastMsg.timestamp.getTime();
            }

            // Generate title if default
            if (activeSessionId && (session.title === 'Resumed Chat' || session.title === 'New Chat')) {
              const firstUserMsg = this.messages.find(m => m.role === 'user');
              if (firstUserMsg) {
                session.title = firstUserMsg.content.substring(0, 30) + (firstUserMsg.content.length > 30 ? '...' : '');
              }
            }

            this.currentSession = session;
            this.chatService.saveSession(session);
            this.loadSessions();
            this.cdr.detectChanges();
            setTimeout(() => this.scrollToBottom(), 100);
          } else {
            // History found but too old
            this.checkRecentSession();
          }
        } else {
          // No history found on backend
          this.checkRecentSession();
        }
      },
      error: (err) => {
        console.error('Failed to fetch history', err);
        this.isLoading = false;
        this.checkRecentSession();
      }
    });
  }

  loadSessions() {
    // Only show sessions that have at least one user message
    this.sessions = this.chatService.getSessions().filter(s =>
      s.messages && s.messages.some(m => m.role === 'user')
    );
    this.cdr.detectChanges();
  }

  checkRecentSession() {
    const recent = this.chatService.getRecentSession();
    if (recent) {
      console.log('Found recent session:', recent.id);
      this.loadSession(recent);
    } else {
      console.log('No recent session found, starting new chat');
      this.startNewChat();
    }
  }

  startNewChat() {
    this.menuCtrl.close('chat-menu');

    // If already in a new chat that hasn't been started, just return
    if (this.currentSession?.title === 'New Chat') {
      return;
    }

    this.currentSession = this.chatService.createSession();
    this.messages = [];
    // Re-filter used ones locally; no refetch (the day's set is already known)
    this.applySuggestions(this.rawSuggestions);
    // No greeting bubble — the empty-state hero (pig + time greeting) takes its place
    this.cdr.detectChanges();
  }

  loadSession(session: ChatSession) {
    console.log('Loading session:', session.id);
    this.menuCtrl.close('chat-menu');
    this.currentSession = session;
    // Create a copy of messages to ensure change detection runs
    this.messages = [...session.messages];
    this.cdr.detectChanges();
    setTimeout(() => this.scrollToBottom(), 100);
  }

  addMessage(msg: ChatMessage, save: boolean = true) {
    this.messages.push(msg);
    if (this.currentSession) {
      this.currentSession.messages = this.messages;
      this.currentSession.lastModified = Date.now();

      // Only save to history if we have at least one user message
      const hasUserMessage = this.messages.some(m => m.role === 'user');

      if (save && hasUserMessage) {
        this.chatService.saveSession(this.currentSession);
        this.loadSessions(); // Refresh list
      }
    }
    this.cdr.detectChanges();
  }

  sendMessage() {
    if (!this.newMessage.trim()) return;

    const userMsg = this.newMessage.trim();
    const userMsgIndex = this.messages.length;

    this.addMessage({
      role: 'user',
      content: userMsg,
      timestamp: new Date(),
      failed: false
    });

    this.newMessage = '';
    this.isLoading = true;
    this.pickThinkingPhrase();
    this.scrollToBottom();

    // Ask for a title whenever this session doesn't have a real one yet —
    // covers the first message AND heals resumed/previously-untitled sessions
    const needsTitle = !this.currentSession
      || this.currentSession.title === 'New Chat'
      || this.currentSession.title === 'Resumed Chat';

    const sessionId = this.currentSession?.id || 'default-session';

    // Push placeholder assistant message
    const assistantMsgIndex = this.messages.length;
    this.addMessage({ role: 'assistant', content: '', timestamp: new Date() });
    this.activeStreamMsg = this.messages[assistantMsgIndex];
    this.cdr.detectChanges();
    this.scrollToBottom();

    // Cancel any in-flight stream
    if (this.streamSubscription) {
      this.streamSubscription.unsubscribe();
      this.streamSubscription = null;
    }

    this.streamSubscription = this.chatService.streamChat(userMsg, sessionId, needsTitle)
      .subscribe({
        next: (event) => {
          if (event.token) {
            // Turn off loading indicator on first token
            if (this.isLoading) {
              this.isLoading = false;
            }
            this.messages[assistantMsgIndex].content += event.token;
            this.cdr.detectChanges();
          }
          if (event.done) {
            this.isLoading = false;
            this.activeStreamMsg = null;
            // Persist the streamed reply regardless of title — history must
            // never depend on title generation succeeding
            if (this.currentSession) {
              if (event.title) {
                this.currentSession.title = event.title;
              }
              this.currentSession.messages = this.messages;
              this.currentSession.lastModified = Date.now();
              this.chatService.saveSession(this.currentSession);
              this.loadSessions();
            }
            this.cdr.detectChanges();
          }
        },
        error: (err) => {
          console.error('[Chat] Stream error:', err);
          this.isLoading = false;
          this.activeStreamMsg = null;
          if (this.messages[userMsgIndex]) {
            this.messages[userMsgIndex].failed = true;
          }
          this.messages[assistantMsgIndex].content = "I'm sorry, I'm having trouble connecting right now. Please try again.";
          this.cdr.detectChanges();
        },
        complete: () => {
          this.isLoading = false;
          this.activeStreamMsg = null;
          // Belt-and-suspenders: if the stream closed without a done event
          // (dropped final chunk), still persist whatever streamed in
          if (this.currentSession && this.messages[assistantMsgIndex]?.content) {
            this.currentSession.messages = this.messages;
            this.currentSession.lastModified = Date.now();
            this.chatService.saveSession(this.currentSession);
            this.loadSessions();
          }
          this.cdr.detectChanges();
        }
      });
  }

  retryMessage(index: number) {
    const failedMsg = this.messages[index];
    if (!failedMsg || failedMsg.role !== 'user' || !failedMsg.failed) return;

    // Remove the failed message and the error response after it
    this.messages.splice(index, 2); // Remove failed user message and error assistant message

    // Update session
    if (this.currentSession) {
      this.currentSession.messages = this.messages;
      this.chatService.saveSession(this.currentSession);
    }

    this.cdr.detectChanges();

    // Resend the message
    this.newMessage = failedMsg.content;
    this.sendMessage();
  }

  scrollToBottom() {
    if (!this.content) return;

    setTimeout(() => {
      const anchor = document.getElementById('scroll-anchor');
      if (anchor) {
        anchor.scrollIntoView({ behavior: 'smooth', block: 'end' });
      } else {
        this.content.scrollToBottom(300);
      }
    }, 100);
  }

  // Auto-scroll textarea to bottom when typing
  onTextareaInput(event: any) {
    // Use setTimeout to ensure the DOM has updated with the new content
    setTimeout(() => {
      const ionTextarea = event.target?.closest('ion-textarea');
      if (ionTextarea) {
        // Scroll the ion-textarea host element (which has overflow-y: auto)
        // Not the native textarea inside (which has overflow: hidden)
        if (ionTextarea.scrollHeight > ionTextarea.clientHeight) {
          ionTextarea.scrollTop = ionTextarea.scrollHeight;
        }
      }
    }, 0);
  }

  /**
   * Split an assistant message into markdown + ```chart segments.
   * Cached per message object so change detection doesn't re-parse (and
   * doesn't churn the DOM) unless the content or streaming state changed.
   */
  private segmentCache = new WeakMap<
    ChatMessage,
    { content: string; streaming: boolean; segments: MessageSegment[] }
  >();
  /** Parsed chart configs keyed by raw fence body — keeps object identity
   *  stable across streaming re-parses so charts don't rebuild per token. */
  private chartConfigCache = new Map<string, ChatChartConfig>();

  segments(msg: ChatMessage): MessageSegment[] {
    const streaming = msg === this.activeStreamMsg;
    const cached = this.segmentCache.get(msg);
    if (cached && cached.content === msg.content && cached.streaming === streaming) {
      return cached.segments;
    }

    const segments = this.parseSegments(msg.content ?? '', streaming);
    this.segmentCache.set(msg, { content: msg.content, streaming, segments });
    return segments;
  }

  trackSegment(index: number, seg: MessageSegment): string {
    return `${index}:${seg.kind}`;
  }

  private parseSegments(content: string, streaming: boolean): MessageSegment[] {
    // While tokens are still arriving, hide an unterminated special block until
    // its fence closes. On a FINISHED message the raw fence stays visible as a
    // code block instead — never silently swallow persisted content.
    if (streaming) {
      for (const opener of ['```chart', '```suggestions', '```action']) {
        const openFence = content.lastIndexOf(opener);
        if (openFence !== -1 && content.indexOf('```', openFence + opener.length) === -1) {
          content = content.slice(0, openFence);
        }
      }
    }

    const segments: MessageSegment[] = [];
    const fence = /```(chart|suggestions|action)\s*\n([\s\S]*?)```/g;
    let last = 0;
    let match: RegExpExecArray | null;

    while ((match = fence.exec(content)) !== null) {
      if (match.index > last) {
        segments.push({ kind: 'md', text: content.slice(last, match.index) });
      }
      if (match[1] === 'chart') {
        const config = this.parseChartConfig(match[2]);
        if (config) {
          segments.push({ kind: 'chart', config });
        } else {
          // Malformed chart JSON — show it as a code block rather than nothing
          segments.push({ kind: 'md', text: '```json\n' + match[2] + '```' });
        }
      } else if (match[1] === 'action') {
        const proposal = this.parseActionProposal(match[2]);
        if (proposal) {
          segments.push({ kind: 'action', proposal, state: 'idle' });
        } else {
          // A proposed-but-unrenderable action must not vanish silently
          segments.push({ kind: 'md', text: '```json\n' + match[2] + '```' });
        }
      } else {
        const options = this.parseSuggestions(match[2]);
        if (options.length) {
          segments.push({ kind: 'suggestions', options });
        }
        // Malformed suggestions are dropped silently — they're optional sugar
      }
      last = fence.lastIndex;
    }
    if (last < content.length) {
      segments.push({ kind: 'md', text: content.slice(last) });
    }
    return segments;
  }

  private parseSuggestions(raw: string): string[] {
    try {
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) return [];
      return parsed
        .filter((o): o is string => typeof o === 'string' && o.trim().length > 0)
        .map(o => o.trim().slice(0, 60))
        .slice(0, 3);
    } catch {
      return [];
    }
  }

  /** Quick-reply chips only make sense on the latest message — older ones would
   *  answer questions the conversation has moved past. */
  isLatestMessage(msg: ChatMessage): boolean {
    return this.messages.length > 0 && this.messages[this.messages.length - 1] === msg;
  }

  sendQuickReply(option: string) {
    if (this.isLoading) return;
    this.newMessage = option;
    this.sendMessage();
  }

  private parseActionProposal(raw: string): ChatActionProposal | null {
    try {
      const parsed = JSON.parse(raw);
      if (
        typeof parsed?.action !== 'string' ||
        !KNOWN_ACTIONS.includes(parsed.action) ||
        typeof parsed?.summary !== 'string' ||
        !parsed.summary.trim()
      ) {
        return null;
      }
      return {
        action: parsed.action,
        params: parsed.params && typeof parsed.params === 'object' ? parsed.params : {},
        summary: parsed.summary.trim().slice(0, 200),
      };
    } catch {
      return null;
    }
  }

  /** Confirm tap on an action card — the ONLY path that executes a FRED proposal. */
  confirmAction(seg: MessageSegment) {
    if (seg.kind !== 'action' || seg.state !== 'idle') return;
    seg.state = 'executing';
    this.cdr.detectChanges();

    const sessionId = this.currentSession?.id || 'default-session';
    this.chatService.confirmAction(seg.proposal.action, seg.proposal.params, sessionId).subscribe({
      next: (res) => {
        seg.state = res.success ? 'done' : 'failed';
        seg.resultMessage = res.message;
        // Mirror the outcome into the conversation (the backend persisted the
        // same text server-side, so history and model context stay consistent)
        this.addMessage({ role: 'assistant', content: res.message, timestamp: new Date() });
        this.cdr.detectChanges();
        this.scrollToBottom();
      },
      error: (err) => {
        console.error('[Chat] Action confirm failed:', err);
        seg.state = 'failed';
        seg.resultMessage = "Couldn't reach the server — nothing was changed.";
        this.cdr.detectChanges();
      },
    });
  }

  cancelAction(seg: MessageSegment) {
    if (seg.kind !== 'action' || seg.state !== 'idle') return;
    seg.state = 'cancelled';
    this.cdr.detectChanges();
  }

  /** Pencil tap: load the chip text into the composer for editing, with the
   *  caret placed at the end so added details flow naturally. */
  async editQuickReply(option: string) {
    this.newMessage = option;
    this.cdr.detectChanges();
    const textarea = this.composerTextarea;
    if (!textarea) return;
    await textarea.setFocus();
    try {
      const native = await textarea.getInputElement();
      native.setSelectionRange(native.value.length, native.value.length);
    } catch { /* caret placement is best-effort */ }
  }

  private parseChartConfig(raw: string): ChatChartConfig | null {
    const cached = this.chartConfigCache.get(raw);
    if (cached) return cached;
    try {
      const config = JSON.parse(raw) as ChatChartConfig;
      const valid =
        Array.isArray(config?.labels) &&
        Array.isArray(config?.datasets) &&
        config.datasets.length > 0 &&
        config.datasets.every(d => d && Array.isArray(d.data));
      if (!valid) return null;
      this.chartConfigCache.set(raw, config);
      return config;
    } catch {
      return null;
    }
  }
}
