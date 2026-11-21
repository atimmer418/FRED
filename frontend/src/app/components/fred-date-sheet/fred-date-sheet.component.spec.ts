/**
 * fred-date-sheet.component.spec.ts
 *
 * Today is pinned to Sunday 2026-11-15 through the `today` input. The ring is checked through what
 * the component asked element.animate for and the styles it committed, against the cells' own
 * offsets rather than fixed pixels (the root's width is Karma's). The fixture auto-detects: the sheet
 * positions its ring in afterNextRender, which runs on the app tick the way it does inside Ionic's
 * modal (whose view is attached to ApplicationRef).
 */
import { ComponentFixture, TestBed, fakeAsync, flush, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ModalController } from '@ionic/angular/standalone';

import {
  FRED_DATE_SHEET_ROLE,
  FredDateSheetComponent,
  FredDateSheetOptions,
  PANE_IN_MS,
  PANE_OUT_MS,
  READOUT_FADE_MS,
  RING_GLIDE_MS,
  RING_PAINT_MS,
  SHAKE_MS,
  presentDateSheet,
} from './fred-date-sheet.component';
import { PlanAmountTrayComponent } from '../plan-amount-tray/plan-amount-tray.component';

/** What the component asked element.animate for, with a hand on its onfinish. */
interface PlayedAnimation {
  target: Element;
  frames: Keyframe[];
  duration: number;
  onfinish: (() => void) | null;
  cancelled: boolean;
}

const TODAY = '2026-11-15';

/** The upcoming chips a WEEKLY schedule gets on 2026-11-15 with business days only. */
const CHIPS = [
  { label: 'Next Mon', date: '2026-11-16' },
  { label: 'Next Fri', date: '2026-11-20' },
  { label: '1st', date: '2026-12-01' },
  { label: '15th', date: '2026-12-15' },
];

describe('FredDateSheetComponent', () => {
  let fixture: ComponentFixture<FredDateSheetComponent>;
  let component: FredDateSheetComponent;
  let modal: jasmine.SpyObj<ModalController>;
  let played: PlayedAnimation[];
  let reducedMotion: boolean;
  let tickSpy: jasmine.Spy;
  let thumpSpy: jasmine.Spy;

  beforeEach(() => {
    // Ionic's Stencil CSS hides the body until ion-app hydrates; no ion-app exists here, and a
    // hidden cell cannot take focus.
    document.documentElement.classList.add('hydrated');
    played = [];
    reducedMotion = false;
    spyOn(window, 'matchMedia').and.callFake((query: string) => ({
      matches: query.includes('prefers-reduced-motion') && reducedMotion,
      media: query,
    } as MediaQueryList));
    spyOn(Element.prototype, 'animate').and.callFake(function (this: Element, frames: Keyframe[] | PropertyIndexedKeyframes | null, options?: number | KeyframeAnimationOptions) {
      const entry: PlayedAnimation = {
        target: this,
        frames: frames as Keyframe[],
        duration: typeof options === 'number' ? options : Number(options?.duration ?? 0),
        onfinish: null,
        cancelled: false,
      };
      played.push(entry);
      return {
        set onfinish(fn: (() => void) | null) { entry.onfinish = fn; },
        get onfinish() { return entry.onfinish; },
        cancel: () => { entry.cancelled = true; },
      } as unknown as Animation;
    });
  });

  afterEach(() => {
    document.querySelectorAll('.modal-stub').forEach(m => m.remove());
  });

  /**
   * `inModal` wraps the host in a display: none stand-in for the ion-modal, as Ionic keeps it
   * until present() starts its animation: the first ring placement then measures a 0x0 cell. The
   * real ion-modal is registered in this bundle and hydrates on its own clock behind a shadow
   * root, so the host's closest('ion-modal') is pointed at the stub instead.
   */
  function create(opts: Partial<FredDateSheetOptions> & { mode: FredDateSheetOptions['mode'] }, inModal = false): void {
    modal = jasmine.createSpyObj('ModalController', ['dismiss']);
    modal.dismiss.and.resolveTo(true);
    TestBed.configureTestingModule({
      imports: [FredDateSheetComponent],
      providers: [{ provide: ModalController, useValue: modal }],
    });
    fixture = TestBed.createComponent(FredDateSheetComponent);
    component = fixture.componentInstance;
    if (inModal) {
      const modalEl = document.createElement('div');
      modalEl.className = 'modal-stub';
      modalEl.style.display = 'none';
      document.body.appendChild(modalEl);
      modalEl.appendChild(fixture.nativeElement);
      spyOn(fixture.nativeElement as HTMLElement, 'closest').and.callFake(
        ((selector: string) => selector === 'ion-modal' ? modalEl : null) as HTMLElement['closest'],
      );
    }
    Object.assign(component, { title: opts.mode === 'birthday' ? 'Date of birth' : 'Transfer date', today: TODAY, ...opts });
    tickSpy = spyOn(component.haptics, 'tick').and.resolveTo();
    thumpSpy = spyOn(component.haptics, 'thump').and.resolveTo();
    fixture.autoDetectChanges();
  }

  const el = (): HTMLElement => fixture.nativeElement;
  const q = <T extends HTMLElement>(selector: string): T => el().querySelector(selector) as T;
  const qa = (selector: string): HTMLElement[] => Array.from(el().querySelectorAll(selector));
  const cell = (date: string): HTMLButtonElement => q(`[data-date="${date}"]`);
  const chip = (label: string): HTMLButtonElement => qa('.chip').find(c => c.querySelector('.l')!.textContent === label) as HTMLButtonElement;
  const tile = (name: string): HTMLButtonElement => qa('.mtile').find(t => t.textContent === name) as HTMLButtonElement;
  const header = (): string => q('.ds-month .t').textContent!;
  const readout = (): HTMLElement => q('.ds-value');
  const done = (): HTMLButtonElement => q('.fd-cta');
  const ring = (): HTMLElement => q('.ds-ring');
  const days = (): HTMLElement => q('.ds-days');
  const months = (): HTMLElement => q('.ds-months');
  const on = (target: Element): PlayedAnimation[] => played.filter(a => a.target === target);
  const tray = (): PlanAmountTrayComponent => fixture.debugElement.query(By.directive(PlanAmountTrayComponent)).componentInstance;

  /** Ends the latest animation on the element the way the browser would. */
  function finish(target: Element): void {
    const last = on(target).filter(a => !a.cancelled).pop();
    if (!last) throw new Error('nothing is playing on that element');
    last.onfinish?.();
    fixture.detectChanges();
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Each mode renders its parts
  // ───────────────────────────────────────────────────────────────────────────

  it('upcoming: handle, zone label, placeholder readout, chips, grid, caption, Done disabled, no rail or Cancel', () => {
    create({ mode: 'upcoming', min: TODAY, businessDaysOnly: true, quickPicks: CHIPS });
    expect(el().getAttribute('role')).toBe('dialog');
    expect(el().getAttribute('aria-modal')).toBe('true');
    expect(el().getAttribute('aria-label')).toBe('Transfer date');
    expect(q('.gs-handle')).not.toBeNull();
    expect(q('.ds-zone').textContent).toBe('Transfer date');
    expect(readout().textContent).toBe('Pick a day');
    expect(readout().classList).toContain('ph');
    expect(q('.ds-sub').textContent).toBe('Tap a quick pick or a day');
    expect(q('.ds-readout').getAttribute('aria-live')).toBe('polite');

    const group = q('.ds-chips');
    expect(group.getAttribute('role')).toBe('radiogroup');
    expect(qa('.chip').map(c => c.getAttribute('role'))).toEqual(['radio', 'radio', 'radio', 'radio']);
    expect(qa('.chip .l').map(c => c.textContent)).toEqual(['Next Mon', 'Next Fri', '1st', '15th']);
    expect(qa('.chip .d').map(c => c.textContent)).toEqual(['Nov 16', 'Nov 20', 'Dec 1', 'Dec 15']);
    expect(chip('Next Fri').getAttribute('aria-label')).toBe('Next Fri, Friday, November 20, 2026');

    expect(header()).toBe('November 2026');
    expect(q('.ds-month').getAttribute('aria-expanded')).toBe('false');
    expect(qa('.ds-wd span').map(s => s.textContent)).toEqual(['S', 'M', 'T', 'W', 'T', 'F', 'S']);
    expect(days().getAttribute('role')).toBe('grid');
    expect(qa('.ds-row').length).toBe(6);
    expect(qa('.ds-row .cell').length).toBe(42);
    expect(qa('.cell[role="gridcell"]').length).toBe(30);
    expect(cell('2026-11-20').getAttribute('aria-label')).toBe('Friday, November 20, 2026');
    expect(cell('2026-11-15').classList).toContain('today');
    expect(cell('2026-11-15').getAttribute('aria-current')).toBe('date');
    expect(months().hidden).toBeTrue();
    expect(q('.ds-note').textContent).toBe('Transfers run on business days.');

    expect(done().textContent).toBe('Use this date');
    expect(done().disabled).toBeTrue();
    expect(q('.gs-cancel')).toBeNull();
    expect(q('app-plan-amount-tray')).toBeNull();
  });

  it('upcoming with no chips renders no radiogroup and an empty note without businessDaysOnly', () => {
    create({ mode: 'upcoming', min: TODAY });
    expect(q('.ds-chips')).toBeNull();
    expect(q('.ds-note').textContent).toBe('');
  });

  it('birthday: the year rail with its caption and live year, the chosen date, the age, Done with the date, Cancel', () => {
    create({ mode: 'birthday', value: '1991-03-14', max: TODAY });
    expect(el().getAttribute('aria-label')).toBe('Date of birth');
    expect(readout().textContent).toBe('Mar 14, 1991');
    expect(readout().classList).not.toContain('ph');
    expect(q('.ds-sub').textContent).toBe('35 years old');
    expect(header()).toBe('March 1991');
    expect(cell('1991-03-14').getAttribute('aria-selected')).toBe('true');
    expect(cell('1991-03-14').classList).toContain('sel');

    const band = q('.ps');
    expect(band.getAttribute('role')).toBe('slider');
    expect(band.getAttribute('aria-label')).toBe('Year of birth');
    expect(band.getAttribute('aria-valuemin')).toBe('1916');
    expect(band.getAttribute('aria-valuemax')).toBe('2026');
    expect(band.getAttribute('aria-valuenow')).toBe('1991');
    expect(band.getAttribute('aria-valuetext')).toBe('1991');
    expect(qa('.ps-ends span').map(s => s.textContent)).toEqual(['1916', '2026']);
    expect(q('.rail-head .cap').textContent).toBe('Year');
    expect(q('.rail-head .live').textContent).toBe('1991');
    expect(tray().gears()).toBe(component.YEAR_GEARS);
    expect(tray().edgeGuard()).toBeFalse();
    expect(tray().scale()).toBe(component.yearScale);
    expect(qa('.nudge').map(b => b.getAttribute('aria-label'))).toEqual(['Earlier year', 'Later year']);

    expect(q('.ds-chips')).toBeNull();
    expect(q('.ds-note').textContent).toBe('');
    expect(done().textContent).toBe('Use Mar 14, 1991');
    expect(done().disabled).toBeFalse();
    expect(q('.gs-cancel').textContent).toBe('Cancel');
  });

  it('the live year turns blue while the rail is dragged', () => {
    create({ mode: 'birthday', value: '1991-03-14', max: TODAY });
    tray().dragging.emit(true);
    fixture.detectChanges();
    expect(q('.rail-head .live').classList).toContain('dragging');
    tray().dragging.emit(false);
    fixture.detectChanges();
    expect(q('.rail-head .live').classList).not.toContain('dragging');
  });

  it('an empty birthday opens on today minus 30 years with nothing chosen and a plain Done', () => {
    create({ mode: 'birthday', max: TODAY });
    expect(header()).toBe('November 1996');
    expect(q('.rail-head .live').textContent).toBe('1996');
    expect(readout().textContent).toBe('Pick a day');
    expect(q('.ds-sub').textContent).toBe('Slide the year, then tap a day');
    expect(done().textContent).toBe('Done');
    expect(done().disabled).toBeTrue();
    expect(qa('[aria-selected="true"]').length).toBe(0);
  });

  it('an empty upcoming opens on the month of min', () => {
    create({ mode: 'upcoming', min: '2027-01-04' });
    expect(header()).toBe('January 2027');
  });

  it('custom subline and doneLabel are used for a chosen date', () => {
    create({
      mode: 'upcoming', min: TODAY, value: '2026-11-20',
      subline: d => `Trades land after ${d}`,
      doneLabel: d => `Pick ${d}`,
    });
    expect(readout().textContent).toBe('Fri, Nov 20');
    expect(q('.ds-sub').textContent).toBe('Trades land after 2026-11-20');
    expect(done().textContent).toBe('Pick 2026-11-20');
  });

  it('the readout names the year when the chosen day is not this year', () => {
    create({ mode: 'upcoming', min: TODAY, value: '2027-01-08' });
    expect(readout().textContent).toBe('Fri, Jan 8, 2027');
    expect(done().textContent).toBe('Use Fri, Jan 8, 2027');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Range and business days
  // ───────────────────────────────────────────────────────────────────────────

  it('min and businessDaysOnly disable the right cells', () => {
    create({ mode: 'upcoming', min: TODAY, businessDaysOnly: true });
    const before = cell('2026-11-14');
    expect(before.classList).toContain('dis');
    expect(before.getAttribute('aria-disabled')).toBe('true');
    expect(before.getAttribute('aria-label')).toBe('Saturday, November 14, 2026, unavailable');
    // Today is a Sunday: in range, but not a business day.
    expect(cell('2026-11-15').getAttribute('aria-disabled')).toBe('true');
    expect(cell('2026-11-21').getAttribute('aria-disabled')).toBe('true');
    expect(cell('2026-11-16').getAttribute('aria-disabled')).toBeNull();
    expect(cell('2026-11-16').classList).not.toContain('dis');
    expect(cell('2026-11-16').classList).not.toContain('weekend');
  });

  it('without businessDaysOnly a weekend in range is selectable and drawn muted', () => {
    create({ mode: 'upcoming', min: TODAY });
    expect(cell('2026-11-21').getAttribute('aria-disabled')).toBeNull();
    expect(cell('2026-11-21').classList).toContain('weekend');
    expect(cell('2026-11-15').getAttribute('aria-disabled')).toBeNull();
  });

  it('max disables the days after it; a birthday draws no weekend muting', () => {
    create({ mode: 'birthday', value: TODAY, max: TODAY });
    expect(cell('2026-11-15').getAttribute('aria-disabled')).toBeNull();
    expect(cell('2026-11-16').getAttribute('aria-disabled')).toBe('true');
    expect(cell('2026-11-14').classList).not.toContain('weekend');
  });

  it('a tap on a disabled cell selects nothing, shakes the cell and thumps', () => {
    create({ mode: 'upcoming', min: TODAY, businessDaysOnly: true });
    const target = cell('2026-11-14');
    target.click();
    fixture.detectChanges();
    expect(component.selected()).toBeNull();
    expect(target.getAttribute('aria-selected')).toBe('false');
    expect(done().disabled).toBeTrue();
    const shake = on(target);
    expect(shake.length).toBe(1);
    expect(shake[0].duration).toBe(SHAKE_MS);
    expect(shake[0].frames.map(f => f['transform'])).toEqual(['translateX(0)', 'translateX(-6px)', 'translateX(6px)', 'translateX(-3px)', 'translateX(0)']);
    expect(thumpSpy).toHaveBeenCalledTimes(1);
    expect(tickSpy).not.toHaveBeenCalled();
    expect(on(ring()).length).toBe(0);
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Choosing a day
  // ───────────────────────────────────────────────────────────────────────────

  it('a day tap selects it: readout, subline, Done, aria-selected, one tick, the ring glides, the readout cross-fades', () => {
    create({ mode: 'upcoming', min: TODAY, businessDaysOnly: true, quickPicks: CHIPS, subline: () => 'Trades land Nov 23 to Nov 25' });
    cell('2026-11-20').click();
    fixture.detectChanges();

    expect(component.selected()).toBe('2026-11-20');
    expect(cell('2026-11-20').getAttribute('aria-selected')).toBe('true');
    expect(qa('[aria-selected="true"]').length).toBe(1);
    expect(readout().textContent).toBe('Fri, Nov 20');
    expect(readout().classList).not.toContain('ph');
    expect(q('.ds-sub').textContent).toBe('Trades land Nov 23 to Nov 25');
    expect(done().textContent).toBe('Use Fri, Nov 20');
    expect(done().disabled).toBeFalse();
    expect(tickSpy).toHaveBeenCalledTimes(1);
    expect(thumpSpy).not.toHaveBeenCalled();

    const glide = on(ring());
    expect(glide.length).toBe(1);
    expect(glide[0].duration).toBe(RING_GLIDE_MS);
    // From hidden it grows in place: both frames sit at the cell, only scale and opacity move.
    expect(glide[0].frames[0]['transform']).toContain('scale(0.6)');
    expect(glide[0].frames[0]['opacity']).toBe('0');
    expect(glide[0].frames[1]['transform']).toContain('scale(1)');
    expect(glide[0].frames[1]['opacity']).toBe('1');
    // The committed style still reads hidden until the glide finishes.
    expect(ring().style.opacity).toBe('0');
    finish(ring());
    expect(ring().style.opacity).toBe('1');
    expect(ring().style.transform).toContain('scale(1)');
    expect(glide[0].cancelled).toBeTrue();

    const fade = on(readout());
    expect(fade.length).toBe(1);
    expect(fade[0].duration).toBe(READOUT_FADE_MS);
    expect(fade[0].frames[0]['opacity']).toBe(0);
    expect(fade[0].frames[1]['opacity']).toBe(1);

    // The matching chip lights up.
    expect(chip('Next Fri').classList).toContain('on');
    expect(chip('Next Fri').getAttribute('aria-checked')).toBe('true');
    expect(chip('Next Mon').getAttribute('aria-checked')).toBe('false');
  });

  it('the chosen number turns white RING_PAINT_MS into the glide; the previous one turns back at once', fakeAsync(() => {
    // The ring is placed from the cell's offsets: a 36px ring centred in the cell, 2px down the 40px row.
    const under = (c: HTMLElement) => `translate(${c.offsetLeft + (c.offsetWidth - 36) / 2}px, ${c.offsetTop + 2}px)`;
    create({ mode: 'upcoming', min: TODAY, value: '2026-11-18' });
    expect(cell('2026-11-18').classList).toContain('sel');
    expect(ring().style.transform).toBe(`${under(cell('2026-11-18'))} scale(1)`);
    // Karma lays the grid out: the third row sits below the first, so the comparison above is not 0 against 0.
    expect(cell('2026-11-18').offsetTop).toBeGreaterThan(cell('2026-11-04').offsetTop);
    cell('2026-11-20').click();
    fixture.detectChanges();
    expect(cell('2026-11-18').classList).not.toContain('sel');
    expect(cell('2026-11-20').classList).not.toContain('sel');
    expect(cell('2026-11-20').getAttribute('aria-selected')).toBe('true');
    tick(RING_PAINT_MS);
    fixture.detectChanges();
    expect(cell('2026-11-20').classList).toContain('sel');
    // A glide between two shown cells moves from the old one to the new one, fully opaque throughout.
    const glide = on(ring())[0];
    expect(glide.frames[0]['transform']).toBe(`${under(cell('2026-11-18'))} scale(1)`);
    expect(glide.frames[1]['transform']).toBe(`${under(cell('2026-11-20'))} scale(1)`);
    expect(cell('2026-11-20').offsetLeft).toBeGreaterThan(cell('2026-11-18').offsetLeft);
    expect(glide.frames[0]['opacity']).toBe('1');
    expect(glide.frames[1]['opacity']).toBe('1');
    flush();
  }));

  it('tapping the chosen day again does nothing', () => {
    create({ mode: 'upcoming', min: TODAY, value: '2026-11-20' });
    cell('2026-11-20').click();
    fixture.detectChanges();
    expect(tickSpy).not.toHaveBeenCalled();
    expect(played.length).toBe(0);
  });

  it('a chip selects its date and highlights; a chip in another month pages the grid with a slide', () => {
    create({ mode: 'upcoming', min: TODAY, businessDaysOnly: true, quickPicks: CHIPS });
    chip('Next Mon').click();
    fixture.detectChanges();
    expect(component.selected()).toBe('2026-11-16');
    expect(chip('Next Mon').classList).toContain('on');
    expect(chip('Next Mon').getAttribute('aria-checked')).toBe('true');
    expect(cell('2026-11-16').getAttribute('aria-selected')).toBe('true');
    expect(tickSpy).toHaveBeenCalledTimes(1);
    finish(ring());

    chip('1st').click();
    fixture.detectChanges();
    expect(component.selected()).toBe('2026-12-01');
    expect(readout().textContent).toBe('Tue, Dec 1');
    expect(tickSpy).toHaveBeenCalledTimes(2);
    // The header moves at once; the grid is still November while it slides out to the left.
    expect(header()).toBe('December 2026');
    expect(cell('2026-11-16')).not.toBeNull();
    expect(cell('2026-12-01')).toBeNull();
    const out = on(days());
    expect(out.length).toBe(1);
    expect(out[0].duration).toBe(PANE_OUT_MS);
    expect(out[0].frames[1]['transform']).toBe('translateX(-24px)');
    expect(out[0].frames[1]['opacity']).toBe(0);

    finish(days());
    expect(header()).toBe('December 2026');
    expect(cell('2026-12-01').getAttribute('aria-selected')).toBe('true');
    expect(cell('2026-12-01').classList).toContain('sel');
    expect(chip('1st').classList).toContain('on');
    expect(chip('Next Mon').classList).not.toContain('on');
    const slideIn = on(days())[1];
    expect(slideIn.duration).toBe(PANE_IN_MS);
    expect(slideIn.frames[0]['transform']).toBe('translateX(24px)');
    expect(slideIn.frames[1]['transform']).toBe('translateX(0)');
    // The ring appears with the new grid, no glide.
    expect(on(ring()).length).toBe(1);
    expect(ring().style.opacity).toBe('1');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Month pane and chevrons
  // ───────────────────────────────────────────────────────────────────────────

  it('the month button flips to the month pane and a tile sets the month without selecting', () => {
    create({ mode: 'upcoming', min: TODAY, businessDaysOnly: true });
    const monthBtn = q('.ds-month');
    monthBtn.click();
    fixture.detectChanges();
    expect(monthBtn.getAttribute('aria-expanded')).toBe('true');
    expect(monthBtn.classList).toContain('up');
    expect(component.pane()).toBe('months');
    // The day grid is still the visible pane while it fades out.
    expect(days().hidden).toBeFalse();
    const out = on(days());
    expect(out.length).toBe(1);
    expect(out[0].duration).toBe(PANE_OUT_MS);
    expect(out[0].frames).toEqual([{ opacity: 1 }, { opacity: 0 }]);

    finish(days());
    expect(days().hidden).toBeTrue();
    expect(months().hidden).toBeFalse();
    const fadeIn = on(months());
    expect(fadeIn.length).toBe(1);
    expect(fadeIn[0].duration).toBe(PANE_IN_MS);
    expect(qa('.mtile').length).toBe(12);
    expect(qa('.mtile').map(t => t.textContent)).toEqual(['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']);
    expect(tile('Nov').classList).toContain('on');
    expect(tile('Nov').getAttribute('aria-pressed')).toBe('true');
    expect(tile('Oct').classList).toContain('dis');
    expect(tile('Oct').getAttribute('aria-disabled')).toBe('true');
    expect(tile('Dec').getAttribute('aria-label')).toBe('December 2026');
    expect(tile('Dec').classList).not.toContain('dis');

    // A disabled tile does nothing.
    tile('Oct').click();
    fixture.detectChanges();
    expect(header()).toBe('November 2026');
    expect(tickSpy).not.toHaveBeenCalled();

    tile('Dec').click();
    fixture.detectChanges();
    expect(tickSpy).toHaveBeenCalledTimes(1);
    expect(header()).toBe('December 2026');
    expect(monthBtn.getAttribute('aria-expanded')).toBe('false');
    expect(component.selected()).toBeNull();
    // No slide for a tile: the grid redraws behind the fading month pane.
    expect(on(days()).length).toBe(1);
    finish(months());
    expect(months().hidden).toBeTrue();
    expect(days().hidden).toBeFalse();
    expect(cell('2026-12-01')).not.toBeNull();
    expect(qa('[aria-selected="true"]').length).toBe(0);
    expect(done().disabled).toBeTrue();
  });

  it('flipping to the month pane hides the ring, flipping back brings it to the chosen day', () => {
    create({ mode: 'upcoming', min: TODAY, value: '2026-11-20' });
    expect(ring().style.opacity).toBe('1');
    q('.ds-month').click();
    fixture.detectChanges();
    expect(ring().style.opacity).toBe('0');
    expect(ring().style.transform).toContain('scale(0.6)');
    finish(days());
    q('.ds-month').click();
    fixture.detectChanges();
    finish(months());
    expect(ring().style.opacity).toBe('1');
    expect(on(ring()).length).toBe(0);
  });

  it('the chevrons page a month each way with a slide, tick, and stop at the range', () => {
    create({ mode: 'upcoming', min: TODAY, businessDaysOnly: true });
    const prev = q<HTMLButtonElement>('.ds-nav-btn.prev');
    const next = q<HTMLButtonElement>('.ds-nav-btn.next');
    expect(prev.disabled).toBeTrue();
    expect(next.disabled).toBeFalse();
    // The ligature names the subset font must carry (scripts/subset-icons.mjs scans templates for them).
    expect(prev.querySelector('.material-symbols-outlined')!.textContent!.trim()).toBe('chevron_left');
    expect(next.querySelector('.material-symbols-outlined')!.textContent!.trim()).toBe('chevron_right');

    next.click();
    fixture.detectChanges();
    expect(tickSpy).toHaveBeenCalledTimes(1);
    expect(on(days())[0].frames[1]['transform']).toBe('translateX(-24px)');
    finish(days());
    expect(header()).toBe('December 2026');
    expect(prev.disabled).toBeFalse();
    expect(on(days())[1].frames[0]['transform']).toBe('translateX(24px)');

    prev.click();
    fixture.detectChanges();
    expect(on(days())[2].frames[1]['transform']).toBe('translateX(24px)');
    finish(days());
    expect(header()).toBe('November 2026');
    expect(on(days())[3].frames[0]['transform']).toBe('translateX(-24px)');
    expect(tickSpy).toHaveBeenCalledTimes(2);
    expect(component.selected()).toBeNull();
  });

  it('a disabled chevron pages nothing even when clicked through', () => {
    create({ mode: 'birthday', value: TODAY, max: TODAY });
    const next = q<HTMLButtonElement>('.ds-nav-btn.next');
    expect(next.disabled).toBeTrue();
    component.page(1);
    fixture.detectChanges();
    expect(header()).toBe('November 2026');
    expect(tickSpy).not.toHaveBeenCalled();
  });

  it('paging across a year boundary', () => {
    create({ mode: 'upcoming', min: '2026-12-01' });
    q('.ds-nav-btn.next').click();
    fixture.detectChanges();
    finish(days());
    expect(header()).toBe('January 2027');
  });

  it('a second tap inside the out-slide pages from the month already committed, not the one still drawn', () => {
    create({ mode: 'upcoming', min: TODAY });
    const next = q<HTMLButtonElement>('.ds-nav-btn.next');
    next.click();
    fixture.detectChanges();
    expect(header()).toBe('December 2026');
    expect(cell('2026-11-16')).not.toBeNull();
    next.click();
    fixture.detectChanges();
    expect(header()).toBe('January 2027');
    expect(tickSpy).toHaveBeenCalledTimes(2);
    // The second tap rides the out-slide already running: no fresh fade-out from full opacity.
    expect(on(days()).length).toBe(1);
    expect(on(days())[0].frames[0]['opacity']).toBe(1);
    finish(days());
    expect(cell('2027-01-01')).not.toBeNull();
    expect(cell('2026-12-01')).toBeNull();
    // One slide-in, not two stacked on the same element.
    expect(on(days()).length).toBe(2);
    expect(on(days())[1].frames[0]['transform']).toBe('translateX(24px)');
    expect(on(days())[1].duration).toBe(PANE_IN_MS);

    // Once the swap has landed, a tap starts a fresh out-slide as usual.
    next.click();
    fixture.detectChanges();
    expect(on(days()).length).toBe(3);
    expect(on(days())[2].duration).toBe(PANE_OUT_MS);
  });

  it('a value months before min opens on its month, and the chevron facing the range stays live until the grid is back in it', () => {
    create({ mode: 'upcoming', value: '2026-08-05', min: TODAY, businessDaysOnly: true, quickPicks: CHIPS });
    const prev = q<HTMLButtonElement>('.ds-nav-btn.prev');
    const next = q<HTMLButtonElement>('.ds-nav-btn.next');
    expect(header()).toBe('August 2026');
    expect(component.selected()).toBeNull();
    expect(qa('.cell:not(.blank)').every(c => c.getAttribute('aria-disabled') === 'true')).toBeTrue();
    expect(prev.disabled).toBeTrue();
    expect(next.disabled).toBeFalse();

    next.click();
    fixture.detectChanges();
    finish(days());
    expect(header()).toBe('September 2026');
    expect(prev.disabled).toBeTrue();
    expect(next.disabled).toBeFalse();

    next.click();
    fixture.detectChanges();
    finish(days());
    next.click();
    fixture.detectChanges();
    finish(days());
    expect(header()).toBe('November 2026');
    expect(prev.disabled).toBeTrue();
    expect(next.disabled).toBeFalse();
    expect(cell('2026-11-20').getAttribute('aria-disabled')).toBeNull();
  });

  it('a value after max opens on its month with only the prev chevron live', () => {
    create({ mode: 'birthday', value: '2027-02-10', max: TODAY });
    expect(header()).toBe('February 2027');
    expect(component.selected()).toBeNull();
    expect(q<HTMLButtonElement>('.ds-nav-btn.next').disabled).toBeTrue();
    expect(q<HTMLButtonElement>('.ds-nav-btn.prev').disabled).toBeFalse();
    q('.ds-nav-btn.prev').click();
    fixture.detectChanges();
    finish(days());
    expect(header()).toBe('January 2027');
    expect(q<HTMLButtonElement>('.ds-nav-btn.next').disabled).toBeTrue();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // The year rail
  // ───────────────────────────────────────────────────────────────────────────

  it('the rail moves the chosen day to the new year, redraws in place, and does not tick again', () => {
    create({ mode: 'birthday', value: '1991-03-14', max: TODAY });
    tray().valueChange.emit(1990);
    fixture.detectChanges();
    expect(component.selected()).toBe('1990-03-14');
    expect(readout().textContent).toBe('Mar 14, 1990');
    expect(q('.ds-sub').textContent).toBe('36 years old');
    expect(done().textContent).toBe('Use Mar 14, 1990');
    expect(header()).toBe('March 1990');
    expect(q('.rail-head .live').textContent).toBe('1990');
    expect(q('.ps').getAttribute('aria-valuenow')).toBe('1990');
    expect(cell('1990-03-14').getAttribute('aria-selected')).toBe('true');
    expect(cell('1990-03-14').classList).toContain('sel');
    expect(q('.ds-note').textContent).toBe('');
    expect(tickSpy).not.toHaveBeenCalled();
    // No slide, no glide, no readout fade: the rail is a continuous control.
    expect(played.length).toBe(0);
    expect(ring().style.opacity).toBe('1');
  });

  it('the rail clamps Feb 29 to Feb 28 with the note; the clamped day is what travels on', () => {
    create({ mode: 'birthday', value: '2024-02-29', max: TODAY });
    tray().valueChange.emit(2023);
    fixture.detectChanges();
    expect(component.selected()).toBe('2023-02-28');
    expect(readout().textContent).toBe('Feb 28, 2023');
    expect(q('.ds-note').textContent).toBe('Feb 29 moved to Feb 28 in 2023.');
    expect(cell('2023-02-28').getAttribute('aria-selected')).toBe('true');

    // Back on a leap year the day stays the 28th (month and day are kept, nothing is remembered) and the note clears.
    tray().valueChange.emit(2024);
    fixture.detectChanges();
    expect(component.selected()).toBe('2024-02-28');
    expect(q('.ds-note').textContent).toBe('');
  });

  it('the rail never moves the day past max, and says where it landed', () => {
    create({ mode: 'birthday', value: '1990-12-25', max: TODAY });
    tray().valueChange.emit(2026);
    fixture.detectChanges();
    expect(component.selected()).toBe('2026-11-15');
    expect(header()).toBe('November 2026');
    expect(q('.rail-head .live').textContent).toBe('2026');
    expect(q('.ds-note').textContent).toBe('Dec 25 moved to Nov 15 in 2026.');
    expect(q('.ds-sub').textContent).toBe('0 years old');
  });

  it('a day tap after a clamp clears the note', () => {
    create({ mode: 'birthday', value: '2024-02-29', max: TODAY });
    tray().valueChange.emit(2023);
    fixture.detectChanges();
    expect(q('.ds-note').textContent).not.toBe('');
    cell('2023-02-10').click();
    fixture.detectChanges();
    expect(q('.ds-note').textContent).toBe('');
    expect(component.selected()).toBe('2023-02-10');
  });

  it('with nothing chosen the rail changes the year on view and leaves nothing selected', () => {
    create({ mode: 'birthday', max: TODAY });
    tray().valueChange.emit(1980);
    fixture.detectChanges();
    expect(header()).toBe('November 1980');
    expect(q('.rail-head .live').textContent).toBe('1980');
    expect(component.selected()).toBeNull();
    expect(done().disabled).toBeTrue();
    expect(played.length).toBe(0);
  });

  it('Home and End on the rail jump to its ends', () => {
    create({ mode: 'birthday', value: '1991-03-14', max: TODAY });
    const band = q('.ps');
    band.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true, cancelable: true }));
    fixture.detectChanges();
    expect(component.selected()).toBe('1916-03-14');
    expect(q('.rail-head .live').textContent).toBe('1916');
    band.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true, cancelable: true }));
    fixture.detectChanges();
    expect(component.selected()).toBe('2026-03-14');
    expect(tickSpy).not.toHaveBeenCalled();
  });

  it('a day tap in another year moves the rail with it', () => {
    create({ mode: 'birthday', value: '1991-12-30', max: TODAY });
    q('.ds-nav-btn.next').click();
    fixture.detectChanges();
    finish(days());
    expect(header()).toBe('January 1992');
    expect(q('.rail-head .live').textContent).toBe('1991');
    cell('1992-01-03').click();
    fixture.detectChanges();
    expect(component.selected()).toBe('1992-01-03');
    expect(q('.rail-head .live').textContent).toBe('1992');
    expect(q('.ps').getAttribute('aria-valuenow')).toBe('1992');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Keyboard on the grid
  // ───────────────────────────────────────────────────────────────────────────

  function arrow(from: HTMLElement, key: string): void {
    from.focus();
    from.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    fixture.detectChanges();
  }

  it('arrows move focus a day or a week, selecting nothing', () => {
    create({ mode: 'upcoming', min: TODAY });
    arrow(cell('2026-11-20'), 'ArrowRight');
    expect(document.activeElement).toBe(cell('2026-11-21'));
    arrow(cell('2026-11-21'), 'ArrowDown');
    expect(document.activeElement).toBe(cell('2026-11-28'));
    arrow(cell('2026-11-28'), 'ArrowLeft');
    expect(document.activeElement).toBe(cell('2026-11-27'));
    arrow(cell('2026-11-27'), 'ArrowUp');
    expect(document.activeElement).toBe(cell('2026-11-20'));
    expect(component.selected()).toBeNull();
    expect(tickSpy).not.toHaveBeenCalled();
  });

  it('an arrow past the month edge pages the grid and lands focus on the new cell', () => {
    create({ mode: 'upcoming', min: TODAY });
    arrow(cell('2026-11-30'), 'ArrowDown');
    // The header moves at once; the grid follows after its out-slide.
    expect(header()).toBe('December 2026');
    expect(cell('2026-12-07')).toBeNull();
    finish(days());
    expect(cell('2026-12-07')).not.toBeNull();
    expect(document.activeElement).toBe(cell('2026-12-07'));
    expect(component.selected()).toBeNull();
  });

  it('arrows never page before min', () => {
    create({ mode: 'upcoming', min: TODAY });
    arrow(cell('2026-11-01'), 'ArrowLeft');
    expect(header()).toBe('November 2026');
    expect(on(days()).length).toBe(0);
    expect(document.activeElement).toBe(cell('2026-11-01'));
    arrow(cell('2026-11-07'), 'ArrowUp');
    expect(header()).toBe('November 2026');
    expect(on(days()).length).toBe(0);
  });

  it('arrows never page after max', () => {
    create({ mode: 'birthday', value: '2026-11-10', max: TODAY });
    arrow(cell('2026-11-30'), 'ArrowRight');
    expect(header()).toBe('November 2026');
    expect(on(days()).length).toBe(0);
    expect(document.activeElement).toBe(cell('2026-11-30'));
  });

  it('other keys on the grid are left alone', () => {
    create({ mode: 'upcoming', min: TODAY });
    const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    cell('2026-11-20').dispatchEvent(event);
    expect(event.defaultPrevented).toBeFalse();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Done and Cancel
  // ───────────────────────────────────────────────────────────────────────────

  it('Done dismisses with the chosen date and the role; nothing is dismissed while Done is disabled', () => {
    create({ mode: 'upcoming', min: TODAY, businessDaysOnly: true });
    component.done();
    expect(modal.dismiss).not.toHaveBeenCalled();
    cell('2026-11-20').click();
    fixture.detectChanges();
    done().click();
    expect(modal.dismiss).toHaveBeenCalledWith({ value: '2026-11-20' }, FRED_DATE_SHEET_ROLE);
  });

  it('Cancel dismisses with no role and no data', () => {
    create({ mode: 'birthday', value: '1991-03-14', max: TODAY });
    q('.gs-cancel').click();
    expect(modal.dismiss).toHaveBeenCalledWith();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // An incoming value that is not selectable
  // ───────────────────────────────────────────────────────────────────────────

  it('a value before min opens on its month with nothing chosen and Done disabled', () => {
    create({ mode: 'upcoming', value: '2026-10-20', min: TODAY, businessDaysOnly: true });
    expect(header()).toBe('October 2026');
    expect(qa('[aria-selected="true"]').length).toBe(0);
    expect(cell('2026-10-20').classList).not.toContain('sel');
    expect(readout().textContent).toBe('Pick a day');
    expect(readout().classList).toContain('ph');
    expect(done().textContent).toBe('Use this date');
    expect(done().disabled).toBeTrue();
    expect(ring().style.opacity).toBe('0');
    // The whole month is behind min, so next pages toward the range and prev cannot leave it further.
    expect(q<HTMLButtonElement>('.ds-nav-btn.prev').disabled).toBeTrue();
    expect(q<HTMLButtonElement>('.ds-nav-btn.next').disabled).toBeFalse();
  });

  it('a weekend value under businessDaysOnly opens on its month unchosen', () => {
    create({ mode: 'upcoming', value: '2026-12-05', min: TODAY, businessDaysOnly: true });
    expect(header()).toBe('December 2026');
    expect(cell('2026-12-05').getAttribute('aria-disabled')).toBe('true');
    expect(cell('2026-12-05').getAttribute('aria-selected')).toBe('false');
    expect(component.selected()).toBeNull();
    expect(done().disabled).toBeTrue();
  });

  it('a selectable incoming value opens chosen, with the ring already in place and no animation', () => {
    create({ mode: 'upcoming', value: '2026-11-20', min: TODAY, businessDaysOnly: true });
    expect(component.selected()).toBe('2026-11-20');
    expect(cell('2026-11-20').classList).toContain('sel');
    expect(ring().style.opacity).toBe('1');
    expect(played.length).toBe(0);
  });

  it('inside a still-hidden modal the ring is measured again on the first shown frame, with no glide', () => {
    let frame: FrameRequestCallback | null = null;
    spyOn(window, 'requestAnimationFrame').and.callFake((cb: FrameRequestCallback) => { frame = cb; return 0; });
    create({ mode: 'upcoming', value: '2026-11-20', min: TODAY, businessDaysOnly: true }, true);
    // display: none measured the cell at 0x0: the ring sits half off the pane's top-left corner.
    expect(ring().style.transform).toBe('translate(-18px, 2px) scale(1)');

    // Ionic emits ionModalWillPresent, then drops overlay-hidden, then the frame the re-measure waited for runs.
    const modalEl = el().parentElement!;
    modalEl.dispatchEvent(new CustomEvent('ionModalWillPresent'));
    expect(frame).not.toBeNull();
    modalEl.style.display = '';
    frame!(0);
    const chosen = cell('2026-11-20');
    expect(chosen.offsetWidth).toBeGreaterThan(0);
    expect(ring().style.transform).toBe(`translate(${chosen.offsetLeft + (chosen.offsetWidth - 36) / 2}px, ${chosen.offsetTop + 2}px) scale(1)`);
    expect(ring().style.opacity).toBe('1');
    expect(played.length).toBe(0);
  });

  it('an explicit undefined businessDaysOnly (Ionic copies componentProps with Object.assign) reads as false', () => {
    create({ mode: 'upcoming', min: TODAY, businessDaysOnly: undefined, quickPicks: undefined });
    expect(cell('2026-11-21').getAttribute('aria-disabled')).toBeNull();
    expect(q('.ds-chips')).toBeNull();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Reduced motion
  // ───────────────────────────────────────────────────────────────────────────

  it('under reduced motion every change snaps with no animate call', () => {
    reducedMotion = true;
    create({ mode: 'upcoming', min: TODAY, businessDaysOnly: true, quickPicks: CHIPS });

    cell('2026-11-20').click();
    fixture.detectChanges();
    expect(component.selected()).toBe('2026-11-20');
    expect(cell('2026-11-20').classList).toContain('sel');
    expect(ring().style.opacity).toBe('1');
    expect(ring().style.transform).toContain('scale(1)');

    cell('2026-11-14').click();
    fixture.detectChanges();
    expect(thumpSpy).toHaveBeenCalledTimes(1);
    expect(component.selected()).toBe('2026-11-20');

    q('.ds-nav-btn.next').click();
    fixture.detectChanges();
    expect(header()).toBe('December 2026');
    expect(ring().style.opacity).toBe('0');

    q('.ds-month').click();
    fixture.detectChanges();
    expect(days().hidden).toBeTrue();
    expect(months().hidden).toBeFalse();
    tile('Nov').click();
    fixture.detectChanges();
    expect(header()).toBe('November 2026');
    expect(days().hidden).toBeFalse();
    expect(ring().style.opacity).toBe('1');

    chip('1st').click();
    fixture.detectChanges();
    expect(header()).toBe('December 2026');
    expect(cell('2026-12-01').classList).toContain('sel');

    expect(played.length).toBe(0);
    expect(tickSpy).toHaveBeenCalledTimes(4);
  });
});

describe('presentDateSheet', () => {
  function fakeModalCtrl(role: string | undefined, data?: unknown) {
    const modalEl = {
      present: jasmine.createSpy('present').and.resolveTo(),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.resolveTo({ role, data }),
    };
    const ctrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    ctrl.create.and.resolveTo(modalEl as unknown as HTMLIonModalElement);
    return { ctrl, modalEl };
  }

  const opts: FredDateSheetOptions = { title: 'Transfer date', mode: 'upcoming', min: TODAY, businessDaysOnly: true };

  it('presents the sheet with the options as componentProps and resolves the date on Done', async () => {
    const { ctrl, modalEl } = fakeModalCtrl(FRED_DATE_SHEET_ROLE, { value: '2026-11-20' });
    expect(await presentDateSheet(ctrl, opts)).toBe('2026-11-20');
    const createArgs = ctrl.create.calls.mostRecent().args[0];
    expect(createArgs.component).toBe(FredDateSheetComponent);
    expect(createArgs.componentProps).toBe(opts);
    expect(createArgs.cssClass).toBe('mc-bottom-sheet-modal');
    expect(createArgs.enterAnimation).toBeDefined();
    expect(createArgs.leaveAnimation).toBeDefined();
    expect(modalEl.present).toHaveBeenCalled();
  });

  it('resolves null on any other dismissal, and on a Done with no value', async () => {
    expect(await presentDateSheet(fakeModalCtrl(undefined).ctrl, opts)).toBeNull();
    expect(await presentDateSheet(fakeModalCtrl('gesture').ctrl, opts)).toBeNull();
    expect(await presentDateSheet(fakeModalCtrl('backdrop').ctrl, opts)).toBeNull();
    expect(await presentDateSheet(fakeModalCtrl(FRED_DATE_SHEET_ROLE, {}).ctrl, opts)).toBeNull();
  });
});
