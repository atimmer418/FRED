# FRED sheets replace every old-style pop-up box, plus the number pad on three ID fields

Date: 2026-11-11. Decided with Andy. Runs AFTER the amount tray rollout (shared files), before skeletons.
Approved look: https://claude.ai/artifact/SYokzqpvrouhrfDnmTQwJC (the "Proposed" phone).

## Intent

No stock Ionic action sheet, Ionic alert, or browser alert/confirm remains in the app. Every one becomes
a FRED bottom sheet in the existing recipe (ModalController + BOTTOM_SHEET_MODAL_OPTS from
utils/bottom-sheet.utils.ts, SheetDragDismissDirective as a hostDirective, the `.gs-*` chrome from
components/goal-remove-confirm-sheet). Three new reusable sheets carry all of it.

## The three reusable sheets (components/)

1. `fred-action-sheet` (the approved design). Inputs: `header: { icon, title, subtitle? }` (40px #eef4ff
   chip with a material symbol, 17px/800 title, 12px/600 #6b7280 subtitle), `actions: { id, icon, label,
   helper?, destructive? }[]`, `cancelLabel` (default "Cancel"). Non-destructive actions render as rows in
   one #f8fafc card (14px radius, 1px #eef1f6, rows separated by 1px #eef1f6): 32px white icon chip
   (#2563EB glyph), 14px/800 label, 11.5px/600 helper, chevron_right in #c3ccd9, :active #eef1f6.
   Destructive actions render in their own card below (white, 1px #fee2e2 border, #fef2f2 chip with a
   #dc2626 glyph, #dc2626 label). A text "Cancel" link under the lists. Dismisses with
   `{ role: 'action', data: { id } }`; drag down, tap outside and Cancel dismiss with no id. Haptic tick
   on an action tap. Role=menu semantics (role="menu"/"menuitem", focus on open).
2. `fred-confirm-sheet`. Inputs: `title`, `body`, `confirmLabel`, `cancelLabel` (default "Not now";
   destructive ones say what keeping means, e.g. "Keep investing"), `tone: 'destructive' | 'primary'`
   (destructive = the red bordered `.gs-remove` button; primary = the black `.fd-cta` button), optional
   `icon` (chip above the title), optional `busy` state (`busyLabel`, spinner inside the button, both
   buttons disabled, drag-to-dismiss and backdrop tap off while busy) for actions that await a server call.
   Dismisses `{ role: 'confirm' }` or nothing. GoalRemoveConfirmSheet stays as it is.
3. `fred-text-prompt-sheet`. Inputs: `title`, `label`, `value`, `maxlength`, `saveLabel` (default
   "Save"). One bottom-border text field (theme/_form-fields.scss recipe), autofocus, Save disabled when
   empty or unchanged, Enter saves. The keyboard IS wanted here: use the same keyboard-lift + gesture-
   suspend pattern the earlier Freedom detail sheet used (KeyboardAvoidDirective on the sheet host stops
   touch propagation while the keyboard is up, so the drag gesture is suspended). Dismisses
   `{ role: 'save', data: { value } }`.

All three: standalone, `host: { class: 'ion-content-scroll-host' }`, `hostDirectives:
[SheetDragDismissDirective]`, Manrope, light only, WAAPI only for any motion of their own, no em
dashes, each with a spec (renders inputs, emits the right dismiss role/data, destructive grouping, busy
state, Save disabled rules).

## Conversions

| Where | Today | Becomes |
|---|---|---|
| pages/goal `openMenu()` | Ionic ActionSheet: Edit plan, Rename, Mark completed/Reopen (not VACATION), Remove goal, Cancel | fred-action-sheet. Header: the goal type's icon, the goal name, subtitle = the hero's monthly plus the status chip text (e.g. "$640/mo · On track"). Rows: Edit plan ("Kids, costs and the return you assume" for kids plans; a type-appropriate helper otherwise), Rename (helper = current name), Mark completed ("Keeps it on your profile as done") or Reopen, then Remove goal ("Its plan goes with it") destructive. Same handlers as today; Remove still chains into GoalRemoveConfirmSheet. Works for every goal type. |
| pages/goal `renameGoal()` | Ionic alert with a text input | fred-text-prompt-sheet (title "Rename goal", label "Name", maxlength 50); same queueWrite on save |
| recurring-investments pause | Ionic alert "Pause Investments?" | fred-confirm-sheet destructive: title "Pause investments?", body as today, confirm "Pause anyway", cancel "Keep investing", icon pause_circle |
| pages/my-profile tier upgrade | Ionic alert "Upgrade to {label}" | fred-confirm-sheet primary: title "Upgrade to {label}?", body as today, confirm "Confirm upgrade", cancel "Not now", icon workspace_premium |
| beneficiaries list delete | Ionic ActionSheet "Delete Beneficiary?" | fred-confirm-sheet destructive: title "Delete this beneficiary?", body "They will no longer be listed on your account.", confirm "Delete", cancel "Keep", busy while the delete call runs; then the same reload + toast as today |
| sell-withdraw close account | inline `.confirm-panel` with warning icon | fred-confirm-sheet destructive with icon warning, title "Close your account?", body as today, confirm "Close account", cancel "Cancel", busy "Closing..." while isClosingAccount; remove the inline panel and its CSS; delete the dead `showConfirmation()` (window.confirm) and the unused AlertController injection |
| components/investment-schedule save error | browser `alert('Error saving investment schedule...')` | `ToastService` danger with the same text; the analytics surface string for that error changes from 'alert' to 'toast' only if the taxonomy allows (check services/analytics/analytics-events.ts; if 'toast' is not a declared surface, keep the event as is and note it) |
| components/portfolio-customize duplicate stock | browser `alert('This stock is already in your portfolio')` | `ToastService` warning, same text |

Nothing else changes on those pages. Every converted flow keeps its analytics events, toasts and
navigation exactly as before.

## Number pad on three ID fields

Add `inputmode="numeric"` (keep `type="text"` and the existing formatters/maxlength) to: beneficiaries/
add-beneficiary.page.html SSN (line ~84) and ZIP (line ~195), and lump-sum-investment.page.html transfer
account number (line ~199). Nothing else about them changes.

## Gates

Specs for the three sheets; each converted page's spec updated so the flow is exercised through the
sheet's dismiss role (e.g. rename saves on role 'save', pause runs on role 'confirm', nothing runs on
dismiss without a role); `npx ng test` on all touched specs prints Executed N of N SUCCESS; grep shows
no AlertController, ActionSheetController, `alert(` or `confirm(` left outside node_modules except
GoalRemoveConfirmSheet's own code; `npx ng build --configuration dev` passes.
