import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild, inject, input } from '@angular/core';
import { TraySlotHost } from './amount-tray-host';
import { PlanAmountTrayComponent } from './plan-amount-tray.component';

/**
 * The tray's slot on a form screen: the wrapper the host grows and collapses (its height, WAAPI),
 * the card that fades inside it, and the tray bound to whatever field the host shows. A page
 * renders it right under the field's row, under `@if (tray.shown())`, so it arrives when a field
 * opens and leaves when the collapse lands; it registers with the host on both. A page with a slot
 * per field names each slot's field, so the host moves between them instead of cross-fading one.
 */
@Component({
  selector: 'app-amount-tray-slot',
  standalone: true,
  imports: [PlanAmountTrayComponent],
  host: { class: 'field-tray' },
  template: `
    <div class="field-tray-in" #inner>
      <app-plan-amount-tray
        [field]="tray().shown()!"
        [value]="tray().value()"
        [scale]="tray().scale()"
        [unit]="tray().unit()"
        [label]="tray().fieldLabel()"
        [valueSuffix]="tray().valueSuffix()"
        (valueChange)="tray().onValue($event)"
        (dragging)="tray().onDragging($event)"
      />
    </div>
  `,
  styles: `
    :host {
      display: block;
      overflow: hidden;
    }

    .field-tray-in {
      padding-top: 10px;
    }
  `,
})
export class AmountTraySlotComponent implements AfterViewInit, OnDestroy {
  readonly tray = input.required<TraySlotHost>();
  /** The field this slot is for, when the page renders one per field. */
  readonly field = input<string>();

  @ViewChild('inner', { static: true }) private inner!: ElementRef<HTMLElement>;
  private readonly el = inject<ElementRef<HTMLElement>>(ElementRef);

  ngAfterViewInit(): void {
    this.tray().attachSlot(this.el.nativeElement, this.inner.nativeElement, this.field());
  }

  ngOnDestroy(): void {
    this.tray().detachSlot(this.el.nativeElement);
  }
}
