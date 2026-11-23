import { ComponentFixture, TestBed } from '@angular/core/testing';
import { QuizSliderComponent } from './quiz-slider.component';

describe('QuizSliderComponent', () => {
  let fixture: ComponentFixture<QuizSliderComponent>;
  let emitted: number[];

  const dollars = (v: number) => '$' + v.toLocaleString('en-US');

  function setup(inputs: Partial<Record<keyof QuizSliderComponent, unknown>>): void {
    fixture = TestBed.createComponent(QuizSliderComponent);
    for (const [key, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(key, value);
    }
    emitted = [];
    fixture.componentInstance.valueChange.subscribe((v: number) => emitted.push(v));
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function moveTrack(position: number): void {
    const range = el().querySelector('input.sim-range') as HTMLInputElement;
    range.value = String(position);
    range.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [QuizSliderComponent] }).compileComponents();
  });

  it('log scale maps the track end points to min and max and snaps to step', () => {
    setup({ value: 1_000_000, min: 100_000, max: 3_000_000, step: 5_000, scale: 'log', format: dollars });

    const range = el().querySelector('input.sim-range') as HTMLInputElement;
    expect(range.min).toBe('0');
    expect(range.max).toBe('1000');

    moveTrack(0);
    moveTrack(1000);
    moveTrack(500);

    // 100000 * 30^0.5 = 547,723, snapped to the 5,000 step.
    expect(emitted).toEqual([100_000, 3_000_000, 550_000]);
    expect(el().querySelector('.big-readout')!.textContent!.trim()).toBe('$550,000');
  });

  it('tapping the readout shows the input, blur commits the typed value and emits it', () => {
    setup({ value: 47_800, min: 5_000, max: 300_000, step: 1_000, format: dollars });

    expect(el().querySelector('.big-input')).toBeNull();
    (el().querySelector('button.big-readout') as HTMLButtonElement).click();
    fixture.detectChanges();

    const input = el().querySelector('input.big-input') as HTMLInputElement;
    expect(input).not.toBeNull();
    expect(input.getAttribute('inputmode')).toBe('numeric');
    expect(input.type).toBe('tel');
    expect(el().querySelector('.big-readout')).toBeNull();

    input.value = '$61,250';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    expect(emitted).toEqual([61_250]);
    expect(el().querySelector('.big-input')).toBeNull();
    expect(el().querySelector('.big-readout')!.textContent!.trim()).toBe('$61,250');
  });

  it('emits valueChange on input and formats the readout with the given formatter', () => {
    setup({
      value: 100_000, min: 0, max: 500_000, step: 10_000, format: dollars,
      hint: 'Per kid', endLabels: ['$0', '$500K'],
    });

    expect(el().querySelector('.big-readout')!.textContent!.trim()).toBe('$100,000');
    expect(el().querySelector('.readout-hint')!.textContent!.trim()).toBe('Per kid');
    const ends = Array.from(el().querySelectorAll('.range-ends span')).map(s => s.textContent!.trim());
    expect(ends).toEqual(['$0', '$500K']);

    moveTrack(250_000);

    expect(emitted).toEqual([250_000]);
    expect(el().querySelector('.big-readout')!.textContent!.trim()).toBe('$250,000');
  });

  it('does not emit when the track lands on the same snapped value', () => {
    setup({ value: 550_000, min: 100_000, max: 3_000_000, step: 5_000, scale: 'log', format: dollars });

    moveTrack(500);

    expect(emitted).toEqual([]);
  });

  it('paints the track fill from the blue-flow tokens, not literal colors', () => {
    setup({ value: 250_000, min: 0, max: 500_000, step: 10_000, format: dollars });

    const range = el().querySelector('input.sim-range') as HTMLInputElement;
    const fill = range.style.getPropertyValue('--track');
    expect(fill).toContain('var(--bf-ink');
    expect(fill).toContain('var(--bf-track');
    expect(fill).toContain('50%');
  });

  it('renders a plain readout with no tap target when tapToType is off', () => {
    setup({ value: 2_000, min: 0, max: 5_000, step: 100, format: dollars, tapToType: false });

    expect(el().querySelector('button.big-readout')).toBeNull();
    expect(el().querySelector('.big-readout')!.textContent!.trim()).toBe('$2,000');
  });
});
