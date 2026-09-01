/**
 * Pure layout math for the gated tour steps. Given the target's rect, produce
 * the padded hole, the four dark panels that tile the viewport around it, and
 * where the tooltip card should sit. No DOM access, so it is unit-testable.
 */

export interface Rect {
  top: number;
  left: number;
  width: number;
  height: number;
}

export interface SpotlightLayout {
  hole: Rect;
  /** Exactly four panels, in order: top, bottom, left, right. */
  panels: Rect[];
  /** Card top edge, px from the viewport top. */
  cardTop: number;
}

/** Breathing room between the target and the hole edge. */
export const HOLE_PAD = 8;
/** Gap between the hole and the card, and the card's minimum top margin. */
export const CARD_GAP = 16;

export function computeSpotlight(
  target: Rect,
  viewportW: number,
  viewportH: number,
  cardHeight: number,
  bottomReserve: number
): SpotlightLayout {
  const clampY = (v: number) => Math.min(viewportH, Math.max(0, v));
  const clampX = (v: number) => Math.min(viewportW, Math.max(0, v));
  const holeTop = clampY(target.top - HOLE_PAD);
  const holeLeft = clampX(target.left - HOLE_PAD);
  const holeBottom = clampY(target.top + target.height + HOLE_PAD);
  const holeRight = clampX(target.left + target.width + HOLE_PAD);

  const hole: Rect = {
    top: holeTop,
    left: holeLeft,
    width: holeRight - holeLeft,
    height: holeBottom - holeTop,
  };

  const panels: Rect[] = [
    { top: 0, left: 0, width: viewportW, height: hole.top },
    { top: holeBottom, left: 0, width: viewportW, height: viewportH - holeBottom },
    { top: hole.top, left: 0, width: hole.left, height: hole.height },
    { top: hole.top, left: holeRight, width: viewportW - holeRight, height: hole.height },
  ];

  const belowTop = holeBottom + CARD_GAP;
  const fitsBelow = belowTop + cardHeight <= viewportH - bottomReserve;
  const cardTop = fitsBelow
    ? belowTop
    : Math.max(CARD_GAP, hole.top - CARD_GAP - cardHeight);

  return { hole, panels, cardTop };
}
