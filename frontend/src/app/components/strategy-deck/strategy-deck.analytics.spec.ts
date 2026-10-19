import { ElementRef } from '@angular/core';
import { ModalController } from '@ionic/angular/standalone';
import { StrategyDeckComponent } from './strategy-deck.component';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';

/**
 * Analytics on the strategy explainer decks.
 *
 * The decks are built directly rather than through TestBed: every event here is fired from a
 * handler the template binds to, so the spec can drive the real handler and assert the exact
 * payload without a component fixture.
 */
describe('StrategyDeckComponent — analytics', () => {

  /** Mock bottom-sheet modal whose onDidDismiss resolves with the given role. */
  function makeSheet(role: string | undefined) {
    return {
      present: jasmine.createSpy('present').and.returnValue(Promise.resolve()),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.returnValue(Promise.resolve({ role })),
    } as unknown as HTMLIonModalElement;
  }

  function build(strategyKey = 'guardrails', userTier: string | null = null) {
    const analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track']);
    const modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['create', 'dismiss']);
    const toast = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);
    const cmp = new StrategyDeckComponent(modalCtrl, toast, analytics);
    cmp.strategyKey = strategyKey;
    cmp.userTier = userTier;
    cmp.ngOnInit();
    return { cmp, analytics, modalCtrl };
  }

  /**
   * Drives the real onScroll handler at a given slide. scrollLeft and clientWidth are defined
   * rather than assigned: a detached element is not scrollable, so a plain write is ignored.
   */
  function scrollToSlide(cmp: StrategyDeckComponent, slide: number): void {
    const el = document.createElement('div');
    Object.defineProperty(el, 'clientWidth', { value: 320, configurable: true });
    Object.defineProperty(el, 'scrollLeft', { value: slide * 320, configurable: true });
    cmp.scrollRef = new ElementRef(el);
    cmp.onScroll();
  }

  it('reports nothing while the user is only scrolling', () => {
    const { cmp, analytics } = build();
    scrollToSlide(cmp, 1);
    scrollToSlide(cmp, 2);
    scrollToSlide(cmp, 3);
    expect(analytics.track).not.toHaveBeenCalled();
    expect(cmp.maxSlideReached).toBe(3);
  });

  it('reports one strategy_viewed when the deck is finished, carrying the furthest slide', async () => {
    const { cmp, analytics } = build('guardrails');
    scrollToSlide(cmp, 1);
    scrollToSlide(cmp, 2);

    await cmp.done();

    expect(analytics.track).toHaveBeenCalledTimes(1);
    expect(analytics.track).toHaveBeenCalledWith({
      name: 'strategy_viewed',
      props: {
        strategy: 'guardrails',
        surface: 'retirement_planning',
        max_slide_reached: 2,
        slide_count: 5,
        exit: 'done',
      },
    });
  });

  it('keeps the furthest slide when the user scrolls back', async () => {
    const { cmp, analytics } = build('yield');
    scrollToSlide(cmp, 4);
    scrollToSlide(cmp, 1);

    await cmp.dismiss();

    expect(analytics.track).toHaveBeenCalledWith({
      name: 'strategy_viewed',
      props: {
        strategy: 'yield',
        surface: 'retirement_planning',
        max_slide_reached: 4,
        slide_count: 5,
        exit: 'cancel',
      },
    });
  });

  it('maps the stress-test exit', async () => {
    const { cmp, analytics } = build('sbloc');

    await cmp.stressTest();

    expect(analytics.track).toHaveBeenCalledWith({
      name: 'strategy_viewed',
      props: {
        strategy: 'sbloc',
        surface: 'retirement_planning',
        max_slide_reached: 0,
        slide_count: 5,
        exit: 'stress_test',
      },
    });
  });

  it('a pro drawdown tap reports the deck exit and no paywall nudge', async () => {
    const { cmp, analytics } = build('annuity', 'pro');

    await cmp.discoverDrawdown();

    expect(analytics.track).toHaveBeenCalledTimes(1);
    expect(analytics.track).toHaveBeenCalledWith({
      name: 'strategy_viewed',
      props: {
        strategy: 'annuity',
        surface: 'retirement_planning',
        max_slide_reached: 0,
        slide_count: 5,
        exit: 'drawdown',
      },
    });
  });

  it('a non-pro drawdown tap reports the gate as shown, then the upgrade and the deck exit', async () => {
    const { cmp, analytics, modalCtrl } = build('yield', 'plus');
    modalCtrl.create.and.returnValue(Promise.resolve(makeSheet('upgrade')));

    await cmp.discoverDrawdown();

    expect(analytics.track).toHaveBeenCalledTimes(3);
    expect(analytics.track.calls.argsFor(0)).toEqual([{
      name: 'paywall_nudge_shown',
      props: { feature: 'optimal_drawdown', tier: 'plus', action: 'shown' },
    }]);
    expect(analytics.track.calls.argsFor(1)).toEqual([{
      name: 'paywall_nudge_shown',
      props: { feature: 'optimal_drawdown', tier: 'plus', action: 'upgrade_tapped' },
    }]);
    expect(analytics.track.calls.argsFor(2)).toEqual([{
      name: 'strategy_viewed',
      props: {
        strategy: 'yield',
        surface: 'retirement_planning',
        max_slide_reached: 0,
        slide_count: 5,
        exit: 'upgrade',
      },
    }]);
  });

  it('maybe-later reports the gate as dismissed and leaves the deck open', async () => {
    const { cmp, analytics, modalCtrl } = build('yield', null);
    modalCtrl.create.and.returnValue(Promise.resolve(makeSheet(undefined)));

    await cmp.discoverDrawdown();

    expect(analytics.track).toHaveBeenCalledTimes(2);
    expect(analytics.track.calls.argsFor(1)).toEqual([{
      name: 'paywall_nudge_shown',
      props: { feature: 'optimal_drawdown', tier: null, action: 'dismissed' },
    }]);
    expect(modalCtrl.dismiss).not.toHaveBeenCalled();
  });

  it('reports an unrecognised plan name as no tier rather than guessing', async () => {
    const { cmp, analytics, modalCtrl } = build('yield', 'founder');
    modalCtrl.create.and.returnValue(Promise.resolve(makeSheet(undefined)));

    await cmp.discoverDrawdown();

    expect(analytics.track.calls.argsFor(0)).toEqual([{
      name: 'paywall_nudge_shown',
      props: { feature: 'optimal_drawdown', tier: null, action: 'shown' },
    }]);
  });
});
