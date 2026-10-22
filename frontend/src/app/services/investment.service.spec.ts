import { HttpClient } from '@angular/common/http';
import { of } from 'rxjs';
import { InvestmentService } from './investment.service';
import { AuthService } from './auth.service';
import { environment } from '../../environments/environment';

/**
 * Scope: ONLY the lump-sum idempotency key (decision 18). Every call generates one UUID and sends
 * it as clientUuid; the backend returns the first row on a retry instead of queueing a second.
 * crypto.randomUUID needs a secure context; Karma's http://localhost counts as one.
 */
describe('InvestmentService createManualInvestment clientUuid', () => {
  const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  let post: jasmine.Spy;
  let service: InvestmentService;

  beforeEach(() => {
    post = jasmine.createSpy('post').and.returnValue(of({ success: true, executionId: 1, queued: true }));
    service = new InvestmentService({ post } as unknown as HttpClient, {} as AuthService);
  });

  it('sends the amount and a v4 clientUuid to /investments/execute', () => {
    service.createManualInvestment(250).subscribe();

    expect(post).toHaveBeenCalledTimes(1);
    const [url, body] = post.calls.mostRecent().args;
    expect(url).toBe(`${environment.backendApiUrl}/investments/execute`);
    expect(body).toEqual({ amount: 250, clientUuid: jasmine.stringMatching(UUID_RE) });
  });

  it('generates a different clientUuid for each call, built once per call', () => {
    const first$ = service.createManualInvestment(250);
    first$.subscribe();
    first$.subscribe();
    service.createManualInvestment(250).subscribe();

    expect(post).toHaveBeenCalledTimes(2);
    expect(post.calls.argsFor(0)[1].clientUuid).toMatch(UUID_RE);
    expect(post.calls.argsFor(1)[1].clientUuid).toMatch(UUID_RE);
    expect(post.calls.argsFor(0)[1].clientUuid).not.toBe(post.calls.argsFor(1)[1].clientUuid);
  });
});
