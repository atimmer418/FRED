import { Component, Input } from '@angular/core';

import { TimelineDot, buildKidTimeline } from '../../pages/goal/kid-timeline.model';

export type KidTimeline = ReturnType<typeof buildKidTimeline>;

interface TimelineLabel {
  dot: TimelineDot;
  /** The small line under the name: the year, "This year", "not yet". */
  sub: string;
  up: boolean;
  /** The first label hangs right of its dot, the last hangs left, so neither leaves the bar. */
  edge: 'l' | 'r' | null;
}

/**
 * The family timeline inside a kids plan's hero: a thin bar from the earliest event to the last
 * 18th birthday, green up to Now, a dot per kid (filled green once passed, dashed when undated),
 * a blue Now dot with a white ring, and "All 18" at the end. Labels alternate above and below the
 * bar so they never collide.
 */
@Component({
  selector: 'app-kid-timeline',
  standalone: true,
  templateUrl: './kid-timeline.component.html',
  styleUrls: ['./kid-timeline.component.scss'],
})
export class KidTimelineComponent {
  @Input() timeline: KidTimeline | null = null;

  get labels(): TimelineLabel[] {
    const dots = this.timeline?.dots ?? [];
    return dots.map((dot, i) => ({
      dot,
      sub: this.subLabel(dot),
      up: i % 2 === 0,
      edge: i === 0 ? 'l' : i === dots.length - 1 ? 'r' : null,
    }));
  }

  /** Ruling R4: "This year" only for a kid dated this year with no month; a dated kid reads its year. */
  private subLabel(dot: TimelineDot): string {
    if (dot.kind === 'kid') {
      if (dot.undated || dot.year === null) return 'not yet';
      if (dot.expectedThisYear) return 'This year';
    }
    return String(dot.year ?? '');
  }
}
