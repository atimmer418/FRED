import { ComponentFixture, TestBed } from '@angular/core/testing';
import { QuizSwitchRowComponent } from './quiz-switch-row.component';

describe('QuizSwitchRowComponent', () => {
  let fixture: ComponentFixture<QuizSwitchRowComponent>;
  let emitted: boolean[];

  function setup(inputs: Record<string, unknown>): void {
    fixture = TestBed.createComponent(QuizSwitchRowComponent);
    const all: Record<string, unknown> = {
      icon: 'school', title: 'College', line: '4 years of tuition, in a 529.', on: true, ...inputs,
    };
    for (const [key, value] of Object.entries(all)) {
      fixture.componentRef.setInput(key, value);
    }
    emitted = [];
    fixture.componentInstance.onChange.subscribe((v: boolean) => emitted.push(v));
    fixture.detectChanges();
  }

  function row(): HTMLElement {
    return fixture.nativeElement.querySelector('.bf-row');
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [QuizSwitchRowComponent] }).compileComponents();
  });

  it('renders role=switch with aria-checked and emits onChange on click', () => {
    setup({ on: true });

    const sw = fixture.nativeElement.querySelector('[role="switch"]') as HTMLElement;
    expect(sw).not.toBeNull();
    expect(sw.getAttribute('aria-checked')).toBe('true');
    expect(fixture.nativeElement.textContent).toContain('College');
    expect(fixture.nativeElement.textContent).toContain('4 years of tuition, in a 529.');
    expect(fixture.nativeElement.querySelector('.material-symbols-outlined').textContent.trim()).toBe('school');

    sw.click();
    expect(emitted).toEqual([false]);

    fixture.componentRef.setInput('on', false);
    fixture.detectChanges();
    expect(sw.getAttribute('aria-checked')).toBe('false');
    expect(row().classList).toContain('off');

    sw.click();
    expect(emitted).toEqual([false, true]);
  });

  it('applies the saves tone class to the line when lineTone is saves', () => {
    setup({ on: false, line: 'Saves you $50K to $135K.' });
    const line = fixture.nativeElement.querySelector('.bf-row-line') as HTMLElement;
    expect(line.classList).not.toContain('saves');

    fixture.componentRef.setInput('lineTone', 'saves');
    fixture.detectChanges();
    expect(line.classList).toContain('saves');
  });

  it('light tone applies the light class on the host row', () => {
    setup({});
    expect(row().classList).not.toContain('light');

    fixture.componentRef.setInput('tone', 'light');
    fixture.detectChanges();
    expect(row().classList).toContain('light');
  });
});
