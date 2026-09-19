// This file can be replaced during build by using the `fileReplacements` array.
// `ng build` replaces `environment.ts` with `environment.prod.ts`.
// The list of file replacements can be found in `angular.json`.

export const environment = {
  production: false, // It's a test build, not a full production build
  local: true,
  backendApiUrl: 'https://local.fredvested.com/api',
  rpId: 'local.fredvested.com', // Your test frontend/app origin hostname
  // Dev tools (?devPage dev auth, simulated passkey). Throwaway value: the local backend
  // sets the same one. Deployed builds leave this empty, which disables dev tooling.
  devToolsKey: 'ci-local-dev-tools'
};

/*
 * For easier debugging in development mode, you can import the following file
 * to ignore zone related error stack frames such as `zone.run`, `zoneDelegate.invokeTask`.
 *
 * This import should be commented out in production mode because it will have a negative impact
 * on performance if an error is thrown.
 */
// import 'zone.js/plugins/zone-error';  // Included with Angular CLI.
