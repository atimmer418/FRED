import { environment } from '../../environments/environment';
import { captureDevToolsKeyFromUrl, resolveDevToolsKey } from './dev-tools-key.utils';

/**
 * The dev-tools key is handed over once in a URL. Two things have to hold: it survives for later
 * launches, and it does not stay in the address bar where it can be screenshotted or pasted into a
 * bug report.
 */
describe('dev-tools-key.utils', () => {
  const STORAGE_KEY = 'fred_dev_tools_key';
  const buildKey = environment.devToolsKey;
  let replaceStateSpy: jasmine.Spy;

  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    (environment as { devToolsKey: string }).devToolsKey = '';
    replaceStateSpy = spyOn(window.history, 'replaceState');
  });

  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    (environment as { devToolsKey: string }).devToolsKey = buildKey;
  });

  it('prefers the build-configured key when there is one (CI)', () => {
    (environment as { devToolsKey: string }).devToolsKey = 'ci-local-dev-tools';
    localStorage.setItem(STORAGE_KEY, 'stored-key');

    expect(resolveDevToolsKey()).toBe('ci-local-dev-tools');
  });

  it('falls back to a previously handed-over key', () => {
    localStorage.setItem(STORAGE_KEY, 'stored-key');

    expect(resolveDevToolsKey()).toBe('stored-key');
  });

  it('returns empty when nothing is configured, which makes the service a no-op', () => {
    expect(resolveDevToolsKey()).toBe('');
  });

  it('stores ?devKey= so later launches do not need it', () => {
    captureDevToolsKeyFromUrl(new URLSearchParams('?devPage=/tabs/tab3&devKey=handed-over'));

    expect(localStorage.getItem(STORAGE_KEY)).toBe('handed-over');
    expect(resolveDevToolsKey()).toBe('handed-over');
  });

  it('strips devKey from the address bar, keeping the other params', () => {
    const originalUrl = `${window.location.pathname}${window.location.search}`;

    // Put a real devKey in the address bar first. The spy has to be let through to do it,
    // then stubbed again so the behaviour under test can be observed rather than applied.
    replaceStateSpy.and.callThrough();
    window.history.replaceState(null, '', '/?devPage=/tabs/tab3&devKey=handed-over');
    replaceStateSpy.and.stub();
    replaceStateSpy.calls.reset();

    captureDevToolsKeyFromUrl(new URLSearchParams(window.location.search));

    expect(replaceStateSpy).toHaveBeenCalled();
    const rewrittenUrl = replaceStateSpy.calls.mostRecent().args[2] as string;
    expect(rewrittenUrl).not.toContain('devKey');
    expect(rewrittenUrl).toContain('devPage');

    // leave the harness URL as it was
    replaceStateSpy.and.callThrough();
    window.history.replaceState(null, '', originalUrl);
  });

  it('does nothing when no devKey is present', () => {
    captureDevToolsKeyFromUrl(new URLSearchParams('?devPage=/tabs/tab3'));

    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(replaceStateSpy).not.toHaveBeenCalled();
  });
});
