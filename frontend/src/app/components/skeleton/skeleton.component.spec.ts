import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SKELETON_SHIMMER_MS, SkeletonComponent } from './skeleton.component';

/** What the component asked element.animate for. */
interface PlayedAnimation {
  target: Element;
  frames: Keyframe[];
  options: KeyframeAnimationOptions;
  cancelled: boolean;
}

@Component({
  standalone: true,
  imports: [SkeletonComponent],
  template: `
    <app-skeleton
      [width]="width()"
      [height]="height()"
      [radius]="radius()"
      [lines]="lines()"
    />
  `,
})
class HostComponent {
  readonly width = signal('100%');
  readonly height = signal('16px');
  readonly radius = signal('8px');
  readonly lines = signal<number | undefined>(undefined);
}

describe('SkeletonComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let played: PlayedAnimation[];
  let reducedMotion: boolean;

  function skeleton(): HTMLElement {
    return (fixture.nativeElement as HTMLElement).querySelector('app-skeleton') as HTMLElement;
  }

  function blocks(): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.sk-block'));
  }

  function shimmers(): Element[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.sk-shimmer'));
  }

  /** Mounts the host, applying `setup` to its inputs before the first render. */
  function create(setup?: (h: HostComponent) => void): void {
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    setup?.(host);
    fixture.detectChanges();
  }

  beforeEach(() => {
    played = [];
    reducedMotion = false;
    spyOn(window, 'matchMedia').and.callFake((query: string) => ({
      matches: query.includes('prefers-reduced-motion') && reducedMotion,
      media: query,
    } as MediaQueryList));
    spyOn(Element.prototype, 'animate').and.callFake(function (
      this: Element,
      frames: Keyframe[] | PropertyIndexedKeyframes | null,
      options?: number | KeyframeAnimationOptions,
    ) {
      const entry: PlayedAnimation = {
        target: this,
        frames: frames as Keyframe[],
        options: typeof options === 'number' ? { duration: options } : (options ?? {}),
        cancelled: false,
      };
      played.push(entry);
      return { cancel: () => { entry.cancelled = true; } } as unknown as Animation;
    });
    TestBed.configureTestingModule({ imports: [HostComponent] });
  });

  afterEach(() => {
    fixture?.destroy();
  });

  describe('inputs', () => {
    it('hides itself from assistive tech', () => {
      create();
      expect(skeleton().getAttribute('aria-hidden')).toBe('true');
    });

    it('renders one block with the defaults', () => {
      create();
      expect(blocks().length).toBe(1);
      expect(skeleton().style.width).toBe('100%');
      expect(blocks()[0].style.height).toBe('16px');
      expect(blocks()[0].style.borderRadius).toBe('8px');
      expect(blocks()[0].classList).not.toContain('sk-block--short');
    });

    it('puts width on the host and height and radius on the block', () => {
      create(h => {
        h.width.set('120px');
        h.height.set('28px');
        h.radius.set('12px');
      });
      expect(skeleton().style.width).toBe('120px');
      expect(blocks()[0].style.height).toBe('28px');
      expect(blocks()[0].style.borderRadius).toBe('12px');
    });

    it('pill is 999px', () => {
      create(h => h.radius.set('pill'));
      expect(blocks()[0].style.borderRadius).toBe('999px');
    });

    it('circle is 50% and takes the height as its width', () => {
      create(h => {
        h.width.set('300px');
        h.height.set('40px');
        h.radius.set('circle');
      });
      expect(blocks()[0].style.borderRadius).toBe('50%');
      expect(skeleton().style.width).toBe('40px');
      expect(blocks()[0].style.height).toBe('40px');
    });
  });

  describe('lines', () => {
    it('stacks n blocks of the given height and shortens the last one', () => {
      create(h => {
        h.lines.set(3);
        h.height.set('14px');
      });
      const all = blocks();
      expect(all.length).toBe(3);
      all.forEach(b => expect(b.style.height).toBe('14px'));
      expect(all[0].classList).not.toContain('sk-block--short');
      expect(all[1].classList).not.toContain('sk-block--short');
      expect(all[2].classList).toContain('sk-block--short');
    });

    it('a single line is a plain block, not a short one', () => {
      create(h => h.lines.set(1));
      expect(blocks().length).toBe(1);
      expect(blocks()[0].classList).not.toContain('sk-block--short');
    });

    it('re-renders when the count changes and sweeps the new blocks', () => {
      create(h => h.lines.set(2));
      expect(blocks().length).toBe(2);
      const before = played.slice();
      expect(before.length).toBe(2);
      host.lines.set(4);
      fixture.detectChanges();
      expect(blocks().length).toBe(4);
      expect(before.every(a => a.cancelled)).toBeTrue();
      const live = played.filter(a => !a.cancelled);
      expect(live.length).toBe(4);
      expect(live.map(a => a.target)).toEqual(shimmers());
    });
  });

  describe('shimmer', () => {
    it('sweeps each block edge to edge, 1400ms, forever, linear', () => {
      create(h => h.lines.set(2));
      expect(played.length).toBe(2);
      const targets = shimmers();
      played.forEach((a, i) => {
        expect(a.target).toBe(targets[i]);
        expect(a.frames).toEqual([{ transform: 'translateX(-100%)' }, { transform: 'translateX(100%)' }]);
        expect(a.options.duration).toBe(SKELETON_SHIMMER_MS);
        expect(a.options.duration).toBe(1400);
        expect(a.options.iterations).toBe(Infinity);
        expect(a.options.easing).toBe('linear');
      });
    });

    it('stays static under reduced motion', () => {
      reducedMotion = true;
      create();
      expect(blocks().length).toBe(1);
      expect(shimmers().length).toBe(1);
      expect(played.length).toBe(0);
    });

    it('cancels the sweep on destroy', () => {
      create(h => h.lines.set(3));
      expect(played.length).toBe(3);
      expect(played.some(a => a.cancelled)).toBeFalse();
      fixture.destroy();
      expect(played.every(a => a.cancelled)).toBeTrue();
    });
  });
});
