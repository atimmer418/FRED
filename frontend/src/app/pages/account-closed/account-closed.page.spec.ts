import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { AccountClosedPage } from './account-closed.page';

describe('AccountClosedPage', () => {
  let router: jasmine.SpyObj<Router>;

  function render(reason: string | null) {
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    TestBed.configureTestingModule({
      imports: [AccountClosedPage],
      providers: [
        { provide: Router, useValue: router },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(reason ? { reason } : {}) } } },
      ],
    });
    const fixture = TestBed.createComponent(AccountClosedPage);
    fixture.detectChanges();
    return fixture;
  }

  const text = (el: HTMLElement) => (el.textContent ?? '').replace(/\s+/g, ' ').trim();

  it('says plainly that the account is closed, and offers one way on', () => {
    const el: HTMLElement = render(null).nativeElement;

    expect(text(el.querySelector('.hero-title')!)).toBe('Your account is closed');
    const buttons = Array.from(el.querySelectorAll('button')).map(text);
    expect(buttons).toEqual(['Start over']);
    expect(el.querySelector('a')!.getAttribute('href')).toContain('mailto:help@fredvested.com');
    // House style: no em dashes in anything the user reads.
    expect(text(el)).not.toContain('—');
  });

  it('Start over goes to Get Started', () => {
    const fixture = render(null);
    fixture.nativeElement.querySelector('button.cta-button').click();

    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/get-started', { replaceUrl: true });
  });

  it('for an unknown passkey it does not claim the account is closed, and offers recovery first', () => {
    const fixture = render('passkey');
    const el: HTMLElement = fixture.nativeElement;

    expect(text(el.querySelector('.hero-title')!)).toBe("We couldn't sign you in");
    expect(Array.from(el.querySelectorAll('button')).map(text)).toEqual(['Recover my account', 'Start a new account']);

    (el.querySelector('button.cta-button') as HTMLButtonElement).click();
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/recovery');
  });
});
