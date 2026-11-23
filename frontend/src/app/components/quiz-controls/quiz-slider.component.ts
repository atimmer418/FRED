import { Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { tickHaptic } from './haptics';

/** The log track runs 0..LOG_TRACK_MAX; positions map to min * (max/min)^(t/LOG_TRACK_MAX). */
const LOG_TRACK_MAX = 1000;

/**
 * A big readout over a slider, the Monte Carlo portfolio step's control.
 * Tapping the readout swaps it for a numeric input so an exact amount is
 * always possible (no keyboard opens otherwise). Values snap to `step`, and
 * each new detent fires a light haptic on native.
 */
@Component({
  selector: 'app-quiz-slider',
  standalone: true,
  templateUrl: './quiz-slider.component.html',
  styleUrls: ['./quiz-slider.component.scss'],
})
export class QuizSliderComponent {
  @Input() value = 0;
  @Input() min = 0;
  @Input() max = 100;
  @Input() step = 1;
  @Input() scale: 'linear' | 'log' = 'linear';
  @Input() format: (v: number) => string = (v) => String(v);
  @Input() hint = '';
  @Input() endLabels?: [string, string];
  @Input() tapToType = true;

  @Output() valueChange = new EventEmitter<number>();

  typing = false;
  private typedRaw = '';

  /** Focus and select the input as soon as it renders, so the keyboard opens on the tap. */
  @ViewChild('typeInput') set typeInput(ref: ElementRef<HTMLInputElement> | undefined) {
    if (!ref) return;
    ref.nativeElement.focus();
    ref.nativeElement.select();
  }

  get trackMin(): number {
    return this.scale === 'log' ? 0 : this.min;
  }

  get trackMax(): number {
    return this.scale === 'log' ? LOG_TRACK_MAX : this.max;
  }

  get trackStep(): number {
    return this.scale === 'log' ? 1 : this.step;
  }

  get trackPosition(): number {
    return this.valueToTrack(this.value);
  }

  /**
   * Paints the track up to the thumb from the blue-flow tokens (--bf-ink, then
   * --bf-track past the thumb). The fallbacks match the token defaults.
   */
  get trackFill(): string {
    const span = this.trackMax - this.trackMin;
    const pct = span > 0 ? ((this.trackPosition - this.trackMin) / span) * 100 : 0;
    const ink = 'var(--bf-ink, #fff)';
    const rest = 'var(--bf-track, rgba(255,255,255,.22))';
    return `linear-gradient(to right, ${ink} 0%, ${ink} ${pct}%, ${rest} ${pct}%)`;
  }

  get typedValue(): string {
    return this.typedRaw;
  }

  onTrackInput(event: Event): void {
    const t = +(event.target as HTMLInputElement).value;
    const next = this.snap(this.trackToValue(t));
    if (next === this.value) return;
    this.value = next;
    this.valueChange.emit(next);
    void tickHaptic();
  }

  startTyping(): void {
    if (!this.tapToType) return;
    this.typedRaw = String(this.value);
    this.typing = true;
  }

  onTypedInput(event: Event): void {
    this.typedRaw = (event.target as HTMLInputElement).value;
  }

  commitTyping(): void {
    if (!this.typing) return;
    this.typing = false;
    const parsed = parseInt(this.typedRaw.replace(/[^0-9]/g, ''), 10);
    if (isNaN(parsed)) return;
    const next = this.clamp(parsed);
    if (next === this.value) return;
    this.value = next;
    this.valueChange.emit(next);
  }

  onTypedKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') (event.target as HTMLElement).blur();
  }

  private trackToValue(t: number): number {
    if (this.scale !== 'log') return t;
    return this.min * Math.pow(this.max / this.min, t / LOG_TRACK_MAX);
  }

  private valueToTrack(v: number): number {
    if (this.scale !== 'log') return v;
    if (v <= this.min) return 0;
    const t = Math.round((LOG_TRACK_MAX * Math.log(v / this.min)) / Math.log(this.max / this.min));
    return Math.min(LOG_TRACK_MAX, t);
  }

  private snap(v: number): number {
    return this.clamp(Math.round(v / this.step) * this.step);
  }

  private clamp(v: number): number {
    return Math.min(this.max, Math.max(this.min, v));
  }
}
