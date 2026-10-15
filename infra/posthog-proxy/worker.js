/**
 * Reverse proxy for PostHog ingestion, served from our own domain.
 *
 * Why this exists:
 * - Ad-block and tracker-block lists match on vendor hostnames, so a direct connection to
 *   PostHog is dropped for a meaningful slice of users. A funnel built on partial data is worse
 *   than no funnel, because it looks trustworthy.
 * - First-party requests keep storage and cookies first-party, which avoids a class of
 *   browser restrictions entirely.
 *
 * The hostname is deliberately neutral (m.fredvested.com). Do not rename it to anything
 * containing posthog, analytics, track, telemetry, metrics or events: those words are themselves
 * on block lists, which would defeat the point.
 *
 * This Worker is a dumb pipe. It adds no logging of its own: request bodies here contain product
 * events, and a Worker log would become a second, unreviewed copy of them.
 */

// PostHog cloud region. Change both to the eu.i / eu-assets.i hosts if the project is in the EU.
const INGEST_HOST = 'us.i.posthog.com';
const ASSET_HOST = 'us-assets.i.posthog.com';

/** Static SDK assets are immutable and worth caching at the edge; ingestion never is. */
const ASSET_PREFIX = '/static/';

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const isAsset = url.pathname.startsWith(ASSET_PREFIX);
    const upstreamHost = isAsset ? ASSET_HOST : INGEST_HOST;

    const upstreamUrl = new URL(request.url);
    upstreamUrl.protocol = 'https:';
    upstreamUrl.hostname = upstreamHost;
    upstreamUrl.port = '';

    const upstreamRequest = new Request(upstreamUrl, request);
    // Upstream routes on Host; without this it sees our domain and 404s.
    upstreamRequest.headers.set('Host', upstreamHost);
    // Our own cookies are irrelevant to the vendor and should not leave the origin.
    upstreamRequest.headers.delete('Cookie');

    if (isAsset) {
      const response = await fetch(upstreamRequest, {
        cf: { cacheTtl: 86400, cacheEverything: true },
      });
      const cached = new Response(response.body, response);
      cached.headers.set('Cache-Control', 'public, max-age=86400');
      return cached;
    }

    return fetch(upstreamRequest);
  },
};
