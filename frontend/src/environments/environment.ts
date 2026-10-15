// This file can be replaced during build by using the `fileReplacements` array.
// `ng build` replaces `environment.ts` with `environment.prod.ts`.
// The list of file replacements can be found in `angular.json`.

export const environment = {
  production: false, // It's a test build, not a full production build
  local: true,
  backendApiUrl: 'https://local.fredvested.com/api',
  rpId: 'local.fredvested.com', // Your test frontend/app origin hostname
  // Empty on purpose. local.fredvested.com is a public hostname whenever the tunnel is up, so a
  // key committed here would be no lock at all. Pass it once as ?devKey=... (see
  // utils/dev-tools-key.utils.ts); only the CI build, which targets localhost, carries a literal.
  devToolsKey: '',
  // PostHog: paste the dev project key here. One project for local, dev and test (the free plan
  // allows one); CI stays empty so renders send nothing. Never hardcode a key in source files.
  posthogKey: '',
  // Our own reverse-proxied host (Cloudflare Worker on the fredvested.com zone), so the SDK is not
  // blocked by ad-block lists and cookies stay first-party. See FREDdocs/posthog-proxy-deploy.md.
  posthogApiHost: 'https://m.fredvested.com'
};

/*
 * For easier debugging in development mode, you can import the following file
 * to ignore zone related error stack frames such as `zone.run`, `zoneDelegate.invokeTask`.
 *
 * This import should be commented out in production mode because it will have a negative impact
 * on performance if an error is thrown.
 */
// import 'zone.js/plugins/zone-error';  // Included with Angular CLI.
