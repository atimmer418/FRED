import { Injectable, signal } from '@angular/core';

import { Goal } from '../models/goal.model';
import { GoalForm } from '../pages/goal/goal-form.model';

export interface GoalDraft {
  form: GoalForm;
  /** The saved goal when the quiz was an edit, else null. */
  existing: Goal | null;
}

/**
 * The quiz's answers on their way to the plan page. The quiz sets the draft before it pushes the
 * plan page; the plan page renders preview mode whenever a draft is present and clears it when the
 * plan is saved or the preview is left. Held in memory only, so a reload of the preview URL finds
 * nothing and falls back to my-profile.
 */
@Injectable({ providedIn: 'root' })
export class GoalDraftService {
  readonly draft = signal<GoalDraft | null>(null);

  set(form: GoalForm, existing: Goal | null): void {
    this.draft.set({ form, existing });
  }

  clear(): void {
    this.draft.set(null);
  }
}
