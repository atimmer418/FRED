import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/**
 * What kind of thing happened. FredToastHostComponent turns it into the chip
 * colour and icon, so a user can read the outcome from the colour alone.
 */
export type ToastSeverity = 'success' | 'error' | 'warning' | 'info' | 'neutral';

export interface ToastRequest {
  severity: ToastSeverity;
  message: string;
  title?: string;
  /**
   * Milliseconds on screen. Defaults to TOAST_DURATION_MS, or ERROR_TOAST_DURATION_MS for an
   * error. An error never shows shorter than ERROR_TOAST_DURATION_MS, whatever is passed.
   */
  duration?: number;
}

/** A request with every default filled in, the shape the host receives. */
export interface ToastItem {
  severity: ToastSeverity;
  message: string;
  title?: string;
  duration: number;
}

export const TOAST_DURATION_MS = 3000;
/** Errors stay up ten seconds: the user has to read what went wrong and decide what to do next. */
export const ERROR_TOAST_DURATION_MS = 10000;

/**
 * The Ionic colour names call sites have always passed to showToast, mapped
 * onto severities. Anything unrecognised reads as info, the old default look.
 */
const SEVERITY_BY_COLOR: Record<string, ToastSeverity> = {
  success: 'success',
  danger: 'error',
  warning: 'warning',
  primary: 'info',
  medium: 'neutral',
};

export function severityForColor(color: string | undefined): ToastSeverity {
  return SEVERITY_BY_COLOR[color ?? ''] ?? 'info';
}

/**
 * ToastService: the one way to put a notification on screen.
 *
 * It owns no DOM. Every request is pushed down items$ and drawn by the single
 * FredToastHostComponent mounted inside ion-app, which stacks, times and
 * dismisses the cards. showToast keeps the signature every call site already
 * uses; show takes a severity directly.
 */
@Injectable({
  providedIn: 'root'
})
export class ToastService {
  private readonly items = new Subject<ToastItem>();

  /** Every toast asked for, in order. FredToastHostComponent is the subscriber. */
  readonly items$: Observable<ToastItem> = this.items.asObservable();

  /**
   * Legacy entry point: an Ionic colour name stands in for the severity. A
   * 'danger' toast stays up the full ten seconds an error gets, even when a
   * call site passes something shorter.
   */
  showToast(message: string, color: string = 'primary', duration?: number): void {
    this.show({ severity: severityForColor(color), message, duration });
  }

  show(request: ToastRequest): void {
    const { severity, message, title } = request;
    const duration = severity === 'error'
      // Errors hold the ten-second floor: a short duration from an older call site would cut them off.
      ? Math.max(request.duration ?? 0, ERROR_TOAST_DURATION_MS)
      : request.duration ?? TOAST_DURATION_MS;
    this.items.next({ severity, message, title, duration });
  }
}
