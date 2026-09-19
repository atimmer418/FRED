import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IonIcon, IonSpinner } from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { flashOutline, checkmarkCircle, closeCircle } from 'ionicons/icons';

/** Shape FRED emits inside ```action fenced blocks (see FredConstitution §6) */
export interface ChatActionProposal {
  action: string;
  params: Record<string, unknown>;
  summary: string;
}

export type ActionCardState = 'idle' | 'executing' | 'done' | 'failed' | 'cancelled';

/**
 * Phase B confirm card: FRED proposes, the user executes. The model never
 * triggers anything — only the Confirm tap here calls the backend.
 */
@Component({
  selector: 'app-chat-action-card',
  standalone: true,
  imports: [CommonModule, IonIcon, IonSpinner],
  template: `
    <div class="action-card" [class.done]="state === 'done'" [class.failed]="state === 'failed'"
      [class.muted]="state === 'cancelled' || (state === 'idle' && !actionable)">
      <div class="action-card-header">
        <ion-icon name="flash-outline"></ion-icon>
        <span>Proposed change</span>
      </div>
      <!-- Derived from the actual params — the model's summary must never be
           the basis of consent (they are independent fields). Summary is only
           a fallback, never shown alongside (reads as saying it twice). -->
      <p class="action-card-summary">{{ derivedFacts || proposal?.summary }}</p>

      <div class="action-card-footer" [ngSwitch]="state">
        <ng-container *ngSwitchCase="'idle'">
          <div class="action-buttons" *ngIf="actionable; else expired">
            <button class="action-confirm" (click)="confirmed.emit()">Confirm</button>
            <button class="action-cancel" (click)="cancelled.emit()">Cancel</button>
          </div>
          <ng-template #expired>
            <p class="action-status">This proposal is no longer active.</p>
          </ng-template>
        </ng-container>

        <div class="action-status-row" *ngSwitchCase="'executing'">
          <ion-spinner name="crescent"></ion-spinner>
          <span>Making the change…</span>
        </div>

        <div class="action-status-row success" *ngSwitchCase="'done'">
          <ion-icon name="checkmark-circle"></ion-icon>
          <span>{{ resultMessage }}</span>
        </div>

        <div class="action-status-row error" *ngSwitchCase="'failed'">
          <ion-icon name="close-circle"></ion-icon>
          <span>{{ resultMessage }}</span>
        </div>

        <p class="action-status" *ngSwitchCase="'cancelled'">Cancelled. Nothing was changed.</p>
      </div>
    </div>
  `,
  styles: [
    `
      .action-card {
        margin: 10px 0;
        padding: 12px 14px;
        background: rgba(37, 99, 235, 0.04);
        border: 1.5px solid rgba(37, 99, 235, 0.35);
        border-radius: 14px;

        &.done {
          background: rgba(5, 150, 105, 0.05);
          border-color: rgba(5, 150, 105, 0.4);
        }

        &.failed {
          background: rgba(220, 38, 38, 0.04);
          border-color: rgba(220, 38, 38, 0.35);
        }

        &.muted {
          background: var(--app-gray-100, #f3f4f6);
          border-color: var(--app-card-border, #e5e5ea);
          opacity: 0.85;
        }
      }

      .action-card-header {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--app-primary, #2563eb);

        ion-icon {
          font-size: 14px;
        }
      }

      .muted .action-card-header {
        color: var(--app-gray-500, #6b7280);
      }

      .action-card-summary {
        margin: 8px 0 10px;
        font-size: 15px;
        font-weight: 700;
        color: var(--app-gray-900, #111827);
        line-height: 1.4;
      }

      .action-buttons {
        display: flex;
        gap: 8px;
      }

      .action-confirm {
        padding: 8px 18px;
        background: var(--app-primary, #2563eb);
        border: none;
        border-radius: 999px;
        font-family: inherit;
        font-size: 14px;
        font-weight: 700;
        color: #ffffff;
        cursor: pointer;
        -webkit-tap-highlight-color: transparent;

        &:active {
          background: #1d4ed8;
        }
      }

      .action-cancel {
        padding: 8px 16px;
        background: transparent;
        border: 1.5px solid var(--app-card-border, #e5e5ea);
        border-radius: 999px;
        font-family: inherit;
        font-size: 14px;
        font-weight: 600;
        color: var(--app-gray-500, #6b7280);
        cursor: pointer;
        -webkit-tap-highlight-color: transparent;

        &:active {
          background: var(--app-gray-100, #f3f4f6);
        }
      }

      .action-status-row {
        display: flex;
        align-items: flex-start;
        gap: 8px;
        font-size: 14px;
        color: var(--app-gray-500, #6b7280);

        ion-icon {
          flex-shrink: 0;
          font-size: 18px;
          margin-top: 1px;
        }

        ion-spinner {
          width: 18px;
          height: 18px;
        }

        &.success {
          color: #059669;
        }

        &.error {
          color: #dc2626;
        }
      }

      .action-status {
        margin: 0;
        font-size: 13px;
        color: var(--app-gray-500, #6b7280);
        font-style: italic;
      }
    `,
  ],
})
export class ChatActionCardComponent {
  @Input() proposal: ChatActionProposal | null = null;
  @Input() state: ActionCardState = 'idle';
  @Input() resultMessage?: string;
  @Input() actionable = false;
  @Output() confirmed = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  constructor() {
    addIcons({ flashOutline, checkmarkCircle, closeCircle });
  }

  /** What confirming ACTUALLY does, computed from params — not the model's prose */
  get derivedFacts(): string {
    switch (this.proposal?.action) {
      case 'update_investment_amount': {
        const amount = Number(this.proposal.params?.['amountPerRun']);
        return isFinite(amount)
          ? `Sets your investment to $${amount.toFixed(2)} per scheduled run`
          : 'Updates your investment amount';
      }
      case 'pause_investing':
        return 'Pauses automatic investing. This resets your investing streak to 0';
      case 'resume_investing':
        return 'Resumes automatic investing on your existing schedule';
      default:
        return '';
    }
  }
}
