import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ModalController } from '@ionic/angular/standalone';

import { RetirementPlanningComponent } from './retirement-planning.component';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { SimulationHistoryService } from '../../services/simulation-history.service';

/** The scaffold spec, with the component's constructor dependencies stubbed the way the drawdown spec does. */
describe('RetirementPlanningComponent', () => {
  let component: RetirementPlanningComponent;
  let fixture: ComponentFixture<RetirementPlanningComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RetirementPlanningComponent],
      providers: [
        { provide: ModalController, useValue: jasmine.createSpyObj('ModalController', ['create']) },
        { provide: ToastService, useValue: jasmine.createSpyObj('ToastService', ['showToast']) },
        {
          provide: AuthService,
          useValue: { userProgress$: of(null), getCurrentUserId: () => 42 },
        },
        {
          provide: SimulationHistoryService,
          useValue: { getHistory: () => [] },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RetirementPlanningComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
