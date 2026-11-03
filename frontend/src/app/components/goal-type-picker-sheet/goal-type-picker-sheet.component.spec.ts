import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModalController } from '@ionic/angular/standalone';
import { GOAL_TYPE_TILES, GoalTypePickerSheetComponent } from './goal-type-picker-sheet.component';

describe('GoalTypePickerSheetComponent', () => {
  let fixture: ComponentFixture<GoalTypePickerSheetComponent>;
  let modal: jasmine.SpyObj<ModalController>;

  beforeEach(() => {
    modal = jasmine.createSpyObj('ModalController', ['dismiss']);
    TestBed.configureTestingModule({
      imports: [GoalTypePickerSheetComponent],
      providers: [{ provide: ModalController, useValue: modal }],
    });
    fixture = TestBed.createComponent(GoalTypePickerSheetComponent);
    fixture.detectChanges();
  });

  it('renders seven tiles in the documented order', () => {
    const tiles: HTMLElement[] = Array.from(fixture.nativeElement.querySelectorAll('.gtp-tile'));
    expect(tiles.length).toBe(7);
    expect(tiles.map(t => (t.querySelector('.gtp-title') as HTMLElement).textContent!.trim())).toEqual([
      'Kids', 'House', 'Emergency fund', 'Car', 'Vacation', 'Wedding', 'Something else',
    ]);
  });

  it('dismisses with role picked and the type on tap', () => {
    const tiles: HTMLElement[] = Array.from(fixture.nativeElement.querySelectorAll('.gtp-tile'));
    tiles[0].click();
    expect(modal.dismiss).toHaveBeenCalledWith('KIDS', 'picked');
  });

  it('dismisses with no role on cancel', () => {
    (fixture.nativeElement.querySelector('.gs-cancel') as HTMLButtonElement).click();
    expect(modal.dismiss).toHaveBeenCalledWith();
  });

  it('has no em dash in any tile copy', () => {
    expect(JSON.stringify(GOAL_TYPE_TILES)).not.toMatch(/\u2014/);
  });
});
