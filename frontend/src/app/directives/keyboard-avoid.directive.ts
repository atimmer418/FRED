import { Directive, ElementRef, HostListener, Input, OnDestroy, OnInit, Optional } from '@angular/core';
import { IonContent } from '@ionic/angular/standalone';
import { Keyboard } from '@capacitor/keyboard';

/**
 * Keeps the focused form field visible above the iOS soft keyboard.
 *
 * Apply to an <ion-content> that contains text inputs:
 *   <ion-content appKeyboardAvoid> ... </ion-content>
 *   <ion-content [appKeyboardAvoid]="'.form-field'"> ... </ion-content>
 * Behavior there: pads the scroll element by the keyboard height and scrolls
 * the focused field above the keyboard by the exact overshoot.
 *
 * BOTTOM-SHEET MODE (no IonContent — used as a hostDirective on a sheet
 * component whose host is the scroller, see ion-modal.mc-bottom-sheet-modal in
 * global.scss): padding can't create scroll room in an auto-height sheet (it
 * just grows the box), and the sheet sits bottom-anchored UNDER the keyboard.
 * Instead this mode:
 *   1. LIFTS the whole ion-modal above the keyboard (translateY on the modal
 *      host — never the .modal-wrapper, whose transform is owned by the sheet
 *      gesture's WAAPI animation and would override inline styles).
 *   2. CAPS the host's max-height to the space above the keyboard so overflow
 *      forms and the content can scroll when the sheet is tall.
 *   3. SUSPENDS the sheet drag gesture while the keyboard is open by stopping
 *      touch/pointer propagation at the host — Ionic's gesture (attached to
 *      .modal-wrapper) otherwise claims every pan, and its onStart steals
 *      focus, collapsing the keyboard mid-scroll.
 *   4. Scrolls the focused field into view by the remaining overshoot.
 *
 * On web the Keyboard plugin never fires, so all of this is a no-op and the
 * global focusin fallback in app.component.ts handles it.
 */
@Directive({
  selector: '[appKeyboardAvoid]',
  standalone: true,
})
export class KeyboardAvoidDirective implements OnInit, OnDestroy {
  /** Optional wrapper selector to bring the whole labeled field (incl. validation
   *  text below the input) into view. Falls back to the focused element. */
  @Input('appKeyboardAvoid') wrapperSelector = '';

  /** Gap (px) kept between the field bottom and the keyboard top. */
  @Input() keyboardAvoidMargin = 16;

  private keyboardHeight = 0;
  private scrollEl?: HTMLElement;
  private modalEl: HTMLElement | null = null;
  private kbShow?: Promise<any>;
  private kbHide?: Promise<any>;

  /** Sheet mode: swallow pans while the keyboard is up (see class docs #3). */
  private readonly blockGesture = (e: Event): void => {
    if (this.keyboardHeight > 0) e.stopPropagation();
  };

  constructor(
    @Optional() private content: IonContent | null,
    private elementRef: ElementRef<HTMLElement>,
  ) {}

  async ngOnInit(): Promise<void> {
    // Cache the scroll element once; the keyboard listeners then just toggle state.
    // On ion-content that's its inner scroll element; otherwise the host itself.
    this.scrollEl = this.content
      ? await this.content.getScrollElement()
      : this.elementRef.nativeElement;

    if (!this.content) {
      this.modalEl = this.elementRef.nativeElement.closest('ion-modal');
      for (const type of ['touchstart', 'touchmove', 'pointerdown', 'pointermove']) {
        this.elementRef.nativeElement.addEventListener(type, this.blockGesture, { passive: true });
      }
    }

    this.kbShow = Keyboard.addListener('keyboardWillShow', info => {
      this.keyboardHeight = info.keyboardHeight;
      if (this.content) {
        if (this.scrollEl) this.scrollEl.style.paddingBottom = `${info.keyboardHeight}px`;
        return;
      }
      // Sheet mode: undo WKWebView's native displacement (it can't reveal an
      // input in a fixed sheet — it only skews hit-testing), then lift + cap.
      window.scrollTo(0, 0);
      if (this.modalEl) {
        this.modalEl.style.transition = 'transform 250ms cubic-bezier(0.32, 0.72, 0, 1)';
        this.modalEl.style.transform = `translateY(-${info.keyboardHeight}px)`;
      }
      if (this.scrollEl) {
        this.scrollEl.style.maxHeight =
          `calc(100dvh - ${info.keyboardHeight + 12}px)`;
      }
    });
    this.kbHide = Keyboard.addListener('keyboardWillHide', () => {
      this.keyboardHeight = 0;
      if (this.content) {
        if (this.scrollEl) this.scrollEl.style.paddingBottom = '';
        return;
      }
      if (this.modalEl) this.modalEl.style.transform = '';
      if (this.scrollEl) this.scrollEl.style.maxHeight = '';
    });
  }

  @HostListener('focusin', ['$event'])
  async onFocusIn(event: FocusEvent): Promise<void> {
    const el = event.target as HTMLElement;
    if (!el?.matches('input, select, textarea')) return;
    await new Promise(r => setTimeout(r, 300)); // let the keyboard finish animating in
    if (!this.keyboardHeight) return;            // web / no keyboard -> no-op

    if (!this.content) window.scrollTo(0, 0);    // sheet mode: re-zero displacement

    const fieldEl =
      (this.wrapperSelector && (el.closest(this.wrapperSelector) as HTMLElement)) || el;
    const rect = fieldEl.getBoundingClientRect();
    // Sheet mode: the modal is lifted above the keyboard, so the visible floor
    // is the keyboard top only if the lift couldn't fully clear it.
    const visibleBottom = window.innerHeight - this.keyboardHeight - this.keyboardAvoidMargin;
    const overshoot = rect.bottom - visibleBottom;
    if (overshoot > 0) {
      if (this.content) (this.content as any).scrollByPoint(0, overshoot, 150);
      else this.scrollEl?.scrollBy({ top: overshoot, behavior: 'smooth' });
    }
  }

  ngOnDestroy(): void {
    for (const type of ['touchstart', 'touchmove', 'pointerdown', 'pointermove']) {
      this.elementRef.nativeElement.removeEventListener(type, this.blockGesture);
    }
    if (this.modalEl) this.modalEl.style.transform = '';
    this.kbShow?.then(h => h.remove());
    this.kbHide?.then(h => h.remove());
  }
}
