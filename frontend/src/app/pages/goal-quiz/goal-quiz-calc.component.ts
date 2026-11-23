import { Component, Input, OnDestroy, OnInit, signal } from '@angular/core';

/** Lines tick on this far apart. */
export const CALC_LINE_STAGGER_MS = 250;

/**
 * The blue "working out this month's number" screen between the last quiz step and the plan
 * preview: a spinning ring and one line per unit of the plan, each ticking from an empty circle
 * to a green check. The parent decides how long the screen stays; this component only ticks.
 * Under reduced motion every line is checked at once.
 */
@Component({
  selector: 'app-goal-quiz-calc',
  standalone: true,
  templateUrl: './goal-quiz-calc.component.html',
  styleUrls: ['./goal-quiz-calc.component.scss'],
})
export class GoalQuizCalcComponent implements OnInit, OnDestroy {
  @Input() lines: string[] = [];
  @Input() reducedMotion = false;

  /** How many lines have ticked on so far. */
  readonly ticked = signal(0);

  private timers: ReturnType<typeof setTimeout>[] = [];

  ngOnInit(): void {
    if (this.reducedMotion) {
      this.ticked.set(this.lines.length);
      return;
    }
    this.lines.forEach((_, i) => {
      this.timers.push(setTimeout(() => this.ticked.set(i + 1), CALC_LINE_STAGGER_MS * (i + 1)));
    });
  }

  ngOnDestroy(): void {
    this.timers.forEach(clearTimeout);
    this.timers = [];
  }
}
