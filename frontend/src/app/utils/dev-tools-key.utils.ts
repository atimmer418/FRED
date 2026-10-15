import { environment } from '../../environments/environment';

/**
 * The dev-tools key that `/api/dev/**` and simulated passkey registration require.
 *
 * CI builds carry the key in `environment.ci.ts` because that backend is an ephemeral container on
 * localhost. Every other build ships an empty key on purpose: the local backend is reachable at
 * https://local.fredvested.com whenever the Cloudflare tunnel is up, so a key committed to the repo
 * would be no lock at all. Supply it once per device with `?devKey=...` (alongside `?devPage=`) and
 * it is remembered from then on.
 */
const STORAGE_KEY = 'fred_dev_tools_key';

/** Reads the key from the build config, else from whatever `?devKey=` stored earlier. */
export function resolveDevToolsKey(): string {
  if (environment.devToolsKey) {
    return environment.devToolsKey;
  }
  try {
    return localStorage.getItem(STORAGE_KEY) ?? '';
  } catch {
    return '';
  }
}

/**
 * Persists `?devKey=` so the next launch does not need it in the URL, then removes it from the
 * address bar. The strip runs even if storing failed: a dev-tools key should never sit in a URL
 * that can be screenshotted, shared, or pasted into a bug report.
 */
export function captureDevToolsKeyFromUrl(params: URLSearchParams): void {
  const key = params.get('devKey');
  if (!key) {
    return;
  }
  try {
    localStorage.setItem(STORAGE_KEY, key);
  } catch {
    // Private mode or blocked storage: the key simply will not persist past this load.
  }
  stripDevKeyFromAddressBar();
}

/** Rewrites the current URL without `devKey`, keeping every other param (`devPage` included). */
function stripDevKeyFromAddressBar(): void {
  try {
    const url = new URL(window.location.href);
    if (!url.searchParams.has('devKey')) {
      return;
    }
    url.searchParams.delete('devKey');
    const query = url.searchParams.toString();
    window.history.replaceState(window.history.state, '', `${url.pathname}${query ? `?${query}` : ''}${url.hash}`);
  } catch {
    // Non-browser context or a blocked History API: nothing to strip.
  }
}
