import { computeSpotlight, HOLE_PAD, CARD_GAP } from './spotlight-geometry';

describe('computeSpotlight', () => {
  const VW = 390;
  const VH = 844;

  it('pads the hole around the target and tiles the viewport with four panels', () => {
    const target = { top: 60, left: 20, width: 52, height: 52 };
    const layout = computeSpotlight(target, VW, VH, 140, 110);

    expect(layout.hole).toEqual({ top: 52, left: 12, width: 68, height: 68 });
    expect(layout.panels.length).toBe(4);

    const [top, bottom, left, right] = layout.panels;
    expect(top).toEqual({ top: 0, left: 0, width: VW, height: 52 });
    expect(bottom).toEqual({ top: 120, left: 0, width: VW, height: VH - 120 });
    expect(left).toEqual({ top: 52, left: 0, width: 12, height: 68 });
    expect(right).toEqual({ top: 52, left: 80, width: VW - 80, height: 68 });

    // Panels + hole cover the viewport exactly once.
    const area = layout.panels.reduce((a, p) => a + p.width * p.height, 0) + layout.hole.width * layout.hole.height;
    expect(area).toBe(VW * VH);
  });

  it('clamps the hole to the viewport for edge targets', () => {
    const target = { top: 2, left: 0, width: 40, height: 40 };
    const layout = computeSpotlight(target, VW, VH, 140, 110);
    expect(layout.hole.top).toBe(0);
    expect(layout.hole.left).toBe(0);
    expect(layout.hole.height).toBe(2 + 40 + HOLE_PAD);
    expect(layout.hole.width).toBe(40 + HOLE_PAD);
    expect(layout.panels[0].height).toBe(0);
    expect(layout.panels[2].width).toBe(0);
  });

  it('collapses the hole to zero size for a target fully outside the viewport', () => {
    const below = computeSpotlight({ top: 900, left: 20, width: 52, height: 50 }, VW, VH, 140, 110);
    expect(below.hole.height).toBe(0);
    expect(below.hole.top).toBe(VH);
    expect(below.panels.every((p: any) => p.width >= 0 && p.height >= 0)).toBeTrue();
    const area = below.panels.reduce((a: number, p: any) => a + p.width * p.height, 0) + below.hole.width * below.hole.height;
    expect(area).toBe(VW * VH);

    const above = computeSpotlight({ top: -100, left: 20, width: 52, height: 50 }, VW, VH, 140, 110);
    expect(above.hole.height).toBe(0);
    expect(above.hole.top).toBe(0);
    expect(above.panels.every((p: any) => p.width >= 0 && p.height >= 0)).toBeTrue();
  });

  it('places the card below the hole when there is room above the bottom reserve', () => {
    const target = { top: 60, left: 20, width: 52, height: 52 };
    const layout = computeSpotlight(target, VW, VH, 140, 110);
    const holeBottom = 52 + 68;
    expect(layout.cardTop).toBe(holeBottom + CARD_GAP);
  });

  it('places the card above the hole when the target sits near the bottom', () => {
    const target = { top: 700, left: 20, width: 350, height: 60 };
    const layout = computeSpotlight(target, VW, VH, 140, 110);
    const holeTop = 700 - HOLE_PAD;
    expect(layout.cardTop).toBe(holeTop - CARD_GAP - 140);
  });

  it('never places the card above the top safe margin', () => {
    const target = { top: 30, left: 20, width: 350, height: 780 };
    const layout = computeSpotlight(target, VW, VH, 140, 110);
    expect(layout.cardTop).toBe(CARD_GAP);
  });
});
