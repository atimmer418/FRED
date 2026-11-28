import { HttpErrorResponse } from '@angular/common/http';
import { serverMessage } from './server-message.utils';

describe('serverMessage', () => {
  const FALLBACK = 'Upload failed. Please try again.';

  it('uses the sentence the server sent, under either key', () => {
    expect(serverMessage(new HttpErrorResponse({ status: 400, error: { message: 'Please upload a JPEG, PNG or PDF.' } }), FALLBACK))
      .toBe('Please upload a JPEG, PNG or PDF.');
    expect(serverMessage(new HttpErrorResponse({ status: 400, error: { error: 'No Alpaca account found' } }), FALLBACK))
      .toBe('No Alpaca account found');
  });

  it('never falls back to the browser\'s own "Http failure response for <address>" text', () => {
    const failures = [
      new HttpErrorResponse({ status: 500, url: 'https://api.example.test/api/alpaca/upload-document' }),
      new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') }),
      new HttpErrorResponse({ status: 400, error: 'plain text body' }),
      new HttpErrorResponse({ status: 400, error: { message: '   ' } }),
      new HttpErrorResponse({ status: 400, error: { message: { nested: true } } }),
      new Error('boom'),
      null,
      undefined,
    ];
    for (const failure of failures) {
      const shown = serverMessage(failure, FALLBACK);
      expect(shown).toBe(FALLBACK);
      expect(shown).not.toContain('Http failure');
    }
  });
});
