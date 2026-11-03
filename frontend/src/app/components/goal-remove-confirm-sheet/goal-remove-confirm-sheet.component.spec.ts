import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModalController } from '@ionic/angular/standalone';
import { GoalRemoveConfirmSheetComponent } from './goal-remove-confirm-sheet.component';

describe('GoalRemoveConfirmSheetComponent', () => {
  let fixture: ComponentFixture<GoalRemoveConfirmSheetComponent>;
  let modal: jasmine.SpyObj<ModalController>;

  beforeEach(() => {
    modal = jasmine.createSpyObj('ModalController', ['dismiss']);
    TestBed.configureTestingModule({
      imports: [GoalRemoveConfirmSheetComponent],
      providers: [{ provide: ModalController, useValue: modal }],
    });
    fixture = TestBed.createComponent(GoalRemoveConfirmSheetComponent);
    fixture.componentInstance.goalName = 'House';
    fixture.detectChanges();
  });

  it('dismisses with role remove on the destructive button and no role on keep', () => {
    (fixture.nativeElement.querySelector('.gs-remove') as HTMLButtonElement).click();
    expect(modal.dismiss).toHaveBeenCalledWith(undefined, 'remove');

    modal.dismiss.calls.reset();
    (fixture.nativeElement.querySelector('.gs-cancel') as HTMLButtonElement).click();
    expect(modal.dismiss).toHaveBeenCalledWith();
  });

  it('shows the heading and body copy', () => {
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Remove this goal?');
    expect(text).toContain('Its plan goes with it. This cannot be undone.');
  });
});
