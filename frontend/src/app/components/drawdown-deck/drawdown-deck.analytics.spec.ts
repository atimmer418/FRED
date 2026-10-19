import { ElementRef } from '@angular/core';
import { of } from 'rxjs';
import { ModalController } from '@ionic/angular/standalone';
import { DrawdownDeckComponent } from './drawdown-deck.component';
import { PortfolioStoreService } from '../../services/portfolio-store.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';

/**
 * Analytics on the Optimal Drawdown deck. Same contract as the strategy decks: one
 * strategy_viewed when the deck closes, never one per slide. The deck is built directly, so no
 * portfolio or localStorage read runs unless a test asks for it.
 */
describe('DrawdownDeckComponent — analytics', () => {

  function build() {
    const analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track']);
    const modalCtrl = jasmine.createSpyObj<ModalController>('ModalController', ['dismiss']);
    const store = jasmine.createSpyObj<PortfolioStoreService>('PortfolioStoreService', ['load$']);
    store.load$.and.returnValue(of());
    const cmp = new DrawdownDeckComponent(modalCtrl, store, analytics);
    return { cmp, analytics, modalCtrl };
  }

  /** Drives the real onScroll handler at a given slide (see the strategy-deck spec for why). */
  function scrollToSlide(cmp: DrawdownDeckComponent, slide: number): void {
    const el = document.createElement('div');
    Object.defineProperty(el, 'clientWidth', { value: 320, configurable: true });
    Object.defineProperty(el, 'scrollLeft', { value: slide * 320, configurable: true });
    cmp.scrollRef = new ElementRef(el);
    cmp.onScroll();
  }

  it('reports nothing while the user is only scrolling', () => {
    const { cmp, analytics } = build();
    scrollToSlide(cmp, 1);
    expect(analytics.track).not.toHaveBeenCalled();
  });

  it('reports one strategy_viewed when the deck is finished', async () => {
    const { cmp, analytics } = build();
    scrollToSlide(cmp, 1);

    await cmp.done();

    expect(analytics.track).toHaveBeenCalledTimes(1);
    expect(analytics.track).toHaveBeenCalledWith({
      name: 'strategy_viewed',
      props: {
        strategy: 'optimal_drawdown',
        surface: 'retirement_planning',
        max_slide_reached: 1,
        slide_count: 2,
        exit: 'done',
      },
    });
  });

  it('maps the close button to the cancel exit', async () => {
    const { cmp, analytics } = build();

    await cmp.dismiss();

    expect(analytics.track).toHaveBeenCalledWith({
      name: 'strategy_viewed',
      props: {
        strategy: 'optimal_drawdown',
        surface: 'retirement_planning',
        max_slide_reached: 0,
        slide_count: 2,
        exit: 'cancel',
      },
    });
  });

  it('maps the stress-test CTA to the stress_test exit', async () => {
    const { cmp, analytics } = build();

    await cmp.stressTest();

    expect(analytics.track).toHaveBeenCalledWith({
      name: 'strategy_viewed',
      props: {
        strategy: 'optimal_drawdown',
        surface: 'retirement_planning',
        max_slide_reached: 0,
        slide_count: 2,
        exit: 'stress_test',
      },
    });
  });
});
