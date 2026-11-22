import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TallyPillsComponent } from './tally-pills.component';

@Component({
  standalone: true,
  imports: [TallyPillsComponent],
  template: `<app-tally-pills [share]="share()" [disabled]="disabled()" (missed)="missed = missed + 1" (doubled)="doubled = doubled + 1" />`,
})
class HostComponent {
  readonly share = signal(450);
  readonly disabled = signal(false);
  missed = 0;
  doubled = 0;
}

describe('TallyPillsComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;

  function pills(): HTMLButtonElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button.tp-pill'));
  }

  /** A pill's icon, label and amount, as three strings. */
  function partsOf(el: Element): string[] {
    return ['.material-symbols-outlined', '.tp-label', '.tp-amount'].map(sel => el.querySelector(sel)!.textContent!.trim());
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders the share on both pills', () => {
    const [missed, doubled] = pills();
    expect(pills().length).toBe(2);
    expect(partsOf(missed)).toEqual(['remove', 'Missed one', '−$450']);
    expect(partsOf(doubled)).toEqual(['add', 'Doubled up', '+$450']);

    host.share.set(1_540);
    fixture.detectChanges();
    expect(partsOf(pills()[0])[2]).toBe('−$1,540');
    expect(partsOf(pills()[1])[2]).toBe('+$1,540');
  });

  it('emits missed and doubled', () => {
    const [missed, doubled] = pills();
    missed.click();
    expect(host.missed).toBe(1);
    expect(host.doubled).toBe(0);
    doubled.click();
    expect(host.doubled).toBe(1);

    host.disabled.set(true);
    fixture.detectChanges();
    expect(pills().every(p => p.disabled)).toBeTrue();
    pills()[0].click();
    expect(host.missed).toBe(1);
  });

  it('hides when the share is 0', () => {
    host.share.set(0);
    fixture.detectChanges();
    const el = (fixture.nativeElement as HTMLElement).querySelector('app-tally-pills') as HTMLElement;
    expect(pills().length).toBe(0);
    expect(getComputedStyle(el).display).toBe('none');

    host.share.set(450);
    fixture.detectChanges();
    expect(pills().length).toBe(2);
    expect(getComputedStyle(el).display).not.toBe('none');
  });
});
