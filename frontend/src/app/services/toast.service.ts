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
  /** Milliseconds on screen. Defaults to TOAST_DURATION_MS, or ERROR_TOAST_DURATION_MS for an error. */
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
export const ERROR_TOAST_DURATION_MS = 4000;

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
   * Legacy entry point: an Ionic colour name stands in for the severity. With
   * no duration the severity's default applies, so a 'danger' toast stays up
   * the extra second an error gets.
   */
  showToast(message: string, color: string = 'primary', duration?: number): void {
    this.show({ severity: severityForColor(color), message, duration });
  }

  show(request: ToastRequest): void {
    const { severity, message, title } = request;
    const duration = request.duration
      ?? (severity === 'error' ? ERROR_TOAST_DURATION_MS : TOAST_DURATION_MS);
    this.items.next({ severity, message, title, duration });
  }
}
