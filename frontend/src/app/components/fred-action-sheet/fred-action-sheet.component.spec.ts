import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModalController } from '@ionic/angular/standalone';

import {
  FRED_ACTION_SHEET_ROLE,
  FredActionSheetAction,
  FredActionSheetComponent,
  presentActionSheet,
} from './fred-action-sheet.component';

const ACTIONS: FredActionSheetAction[] = [
  { id: 'edit', icon: 'tune', label: 'Edit plan', helper: 'Kids, costs and the return you assume' },
  { id: 'rename', icon: 'edit', label: 'Rename', helper: 'Family plan' },
  { id: 'remove', icon: 'delete', label: 'Remove goal', helper: 'Its plan goes with it', destructive: true },
];

describe('FredActionSheetComponent', () => {
  let fixture: ComponentFixture<FredActionSheetComponent>;
  let modal: jasmine.SpyObj<ModalController>;

  function create(actions: FredActionSheetAction[] = ACTIONS, props: Partial<Pick<FredActionSheetComponent, 'cancelLabel'>> = {}): void {
    modal = jasmine.createSpyObj('ModalController', ['dismiss']);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [FredActionSheetComponent],
      providers: [{ provide: ModalController, useValue: modal }],
    });
    fixture = TestBed.createComponent(FredActionSheetComponent);
    fixture.componentInstance.header = { icon: 'family_restroom', title: 'Family plan', subtitle: '$640/mo · On track' };
    fixture.componentInstance.actions = actions;
    Object.assign(fixture.componentInstance, props);
    fixture.detectChanges();
  }

  function rows(root: HTMLElement): HTMLButtonElement[] {
    return Array.from(root.querySelectorAll<HTMLButtonElement>('.fs-row'));
  }

  it('renders the header chip, title and subtitle', () => {
    create();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.fs-chip .material-symbols-outlined')!.textContent!.trim()).toBe('family_restroom');
    expect(el.querySelector('.fs-head h3')!.textContent).toBe('Family plan');
    expect(el.querySelector('.fs-head p')!.textContent).toBe('$640/mo · On track');
  });

  it('groups non-destructive rows in the first card and destructive rows in the red card', () => {
    create();
    const el: HTMLElement = fixture.nativeElement;
    const cards = Array.from(el.querySelectorAll<HTMLElement>('.fs-list'));
    expect(cards.length).toBe(2);
    expect(cards[1].classList).toContain('fs-list--danger');
    expect(rows(cards[0]).map(r => r.querySelector('b')!.textContent)).toEqual(['Edit plan', 'Rename']);
    expect(rows(cards[1]).map(r => r.querySelector('b')!.textContent)).toEqual(['Remove goal']);
    expect(rows(cards[0])[0].querySelector('.fs-row-text span')!.textContent).toBe('Kids, costs and the return you assume');
    expect(rows(cards[0])[0].querySelector('.fs-row-icon .material-symbols-outlined')!.textContent!.trim()).toBe('tune');
  });

  it('renders no red card when nothing is destructive, and no row card when everything is', () => {
    create(ACTIONS.filter(a => !a.destructive));
    expect(fixture.nativeElement.querySelector('.fs-list--danger')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.fs-list').length).toBe(1);

    create(ACTIONS.filter(a => a.destructive));
    const cards = Array.from(fixture.nativeElement.querySelectorAll('.fs-list')) as HTMLElement[];
    expect(cards.length).toBe(1);
    expect(cards[0].classList).toContain('fs-list--danger');
  });

  it('draws the row focus ring inside the row so the card overflow clip cannot hide it', () => {
    create();
    // Karma has no layout, so read the compiled component stylesheet rather than a rendered ring.
    const rules: CSSStyleRule[] = [];
    for (const sheet of Array.from(document.styleSheets)) {
      for (const rule of Array.from(sheet.cssRules)) {
        if (rule instanceof CSSStyleRule) rules.push(rule);
      }
    }
    const focusRule = rules.find(r => r.selectorText.includes('.fs-row') && r.selectorText.includes(':focus-visible'));
    expect(focusRule).withContext('.fs-row:focus-visible rule').toBeDefined();
    expect(focusRule!.style.outlineOffset).toBe('-2px');
  });

  it('dismisses with role action and the row id on tap', () => {
    create();
    rows(fixture.nativeElement)[1].click();
    expect(modal.dismiss).toHaveBeenCalledWith({ id: 'rename' }, FRED_ACTION_SHEET_ROLE);

    modal.dismiss.calls.reset();
    rows(fixture.nativeElement)[2].click();
    expect(modal.dismiss).toHaveBeenCalledWith({ id: 'remove' }, FRED_ACTION_SHEET_ROLE);
  });

  it('dismisses with no role on Cancel, and the cancel label is an input', () => {
    create(ACTIONS, { cancelLabel: 'Close' });
    const cancel = fixture.nativeElement.querySelector('.gs-cancel') as HTMLButtonElement;
    expect(cancel.textContent).toBe('Close');
    cancel.click();
    expect(modal.dismiss).toHaveBeenCalledWith();

    create();
    expect(fixture.nativeElement.querySelector('.gs-cancel').textContent).toBe('Cancel');
  });

  it('falls back to Cancel when the label is passed as undefined', () => {
    // Ionic's attachComponent copies componentProps with Object.assign, so the undefined reaches the input.
    create(ACTIONS, { cancelLabel: undefined });
    expect(fixture.nativeElement.querySelector('.gs-cancel').textContent).toBe('Cancel');
  });

  it('has menu semantics and focuses the first row when the sheet lands', () => {
    create();
    const el: HTMLElement = fixture.nativeElement;
    const menu = el.querySelector('[role="menu"]')!;
    expect(menu.getAttribute('aria-labelledby')).toBe(fixture.componentInstance.titleId);
    expect(el.querySelector('h3')!.id).toBe(fixture.componentInstance.titleId);
    expect(el.querySelectorAll('[role="menuitem"]').length).toBe(3);

    // Karma's context has no layout, so focus() cannot land: assert the call, not activeElement.
    const focus = spyOn(rows(el)[0], 'focus');
    fixture.componentInstance.ionViewDidEnter();
    expect(focus).toHaveBeenCalledWith({ preventScroll: true });
  });

  describe('menu keyboard contract', () => {
    let items: HTMLButtonElement[];
    let focused: jasmine.Spy[];
    let activeElement: jasmine.Spy;

    beforeEach(() => {
      create();
      items = rows(fixture.nativeElement);
      focused = items.map(r => spyOn(r, 'focus'));
      activeElement = spyOnProperty(document, 'activeElement', 'get');
    });

    /** Fires the key from the given row, as activeElement cannot be set without layout. */
    function press(key: string, from: number): KeyboardEvent {
      activeElement.and.returnValue(items[from]);
      const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      items[from].dispatchEvent(event);
      return event;
    }

    it('ArrowDown moves focus to the next row and wraps past the last', () => {
      expect(press('ArrowDown', 0).defaultPrevented).toBeTrue();
      expect(focused[1]).toHaveBeenCalledWith({ preventScroll: true });
      // The destructive card is part of the same menu, so the walk crosses into it and back around.
      expect(press('ArrowDown', 2).defaultPrevented).toBeTrue();
      expect(focused[0]).toHaveBeenCalledWith({ preventScroll: true });
    });

    it('ArrowUp moves focus to the previous row and wraps before the first', () => {
      press('ArrowUp', 0);
      expect(focused[2]).toHaveBeenCalledWith({ preventScroll: true });
      expect(focused[0]).not.toHaveBeenCalled();
    });

    it('Home and End jump to the first and last rows', () => {
      press('End', 0);
      expect(focused[2]).toHaveBeenCalledWith({ preventScroll: true });
      press('Home', 2);
      expect(focused[0]).toHaveBeenCalledWith({ preventScroll: true });
    });

    it('leaves other keys alone so Tab and Enter keep their browser behaviour', () => {
      expect(press('Tab', 0).defaultPrevented).toBeFalse();
      expect(press('Enter', 0).defaultPrevented).toBeFalse();
      for (const spy of focused) expect(spy).not.toHaveBeenCalled();
    });
  });
});

describe('presentActionSheet', () => {
  function fakeModalCtrl(role: string | undefined, data?: unknown) {
    const modalEl = {
      present: jasmine.createSpy('present').and.resolveTo(),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.resolveTo({ role, data }),
    };
    const ctrl = jasmine.createSpyObj<ModalController>('ModalController', ['create']);
    ctrl.create.and.resolveTo(modalEl as unknown as HTMLIonModalElement);
    return { ctrl, modalEl };
  }

  it('presents the sheet with the options as componentProps and resolves the tapped id', async () => {
    const { ctrl, modalEl } = fakeModalCtrl('action', { id: 'rename' });
    const opts = { header: { icon: 'flag', title: 'House' }, actions: ACTIONS };
    const picked = await presentActionSheet(ctrl, opts);
    expect(picked).toBe('rename');
    const createArgs = ctrl.create.calls.mostRecent().args[0];
    expect(createArgs.component).toBe(FredActionSheetComponent);
    expect(createArgs.componentProps).toBe(opts);
    expect(createArgs.cssClass).toBe('mc-bottom-sheet-modal');
    expect(modalEl.present).toHaveBeenCalled();
  });

  it('resolves null when the sheet is dismissed without the action role', async () => {
    expect(await presentActionSheet(fakeModalCtrl(undefined).ctrl, { header: { icon: 'flag', title: 'x' }, actions: ACTIONS })).toBeNull();
    expect(await presentActionSheet(fakeModalCtrl('gesture').ctrl, { header: { icon: 'flag', title: 'x' }, actions: ACTIONS })).toBeNull();
    expect(await presentActionSheet(fakeModalCtrl('backdrop').ctrl, { header: { icon: 'flag', title: 'x' }, actions: ACTIONS })).toBeNull();
  });
});
