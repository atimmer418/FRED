import { Capacitor } from '@capacitor/core';
import { Haptics, ImpactStyle } from '@capacitor/haptics';

/**
 * A light tick for a slider detent. Native only (a no-op on web), and never
 * throws: a haptic is a nicety, so a failure must not break the control.
 */
export async function tickHaptic(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  try {
    await Haptics.impact({ style: ImpactStyle.Light });
  } catch {
    // Ignored on purpose.
  }
}
