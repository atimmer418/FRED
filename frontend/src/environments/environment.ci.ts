// CI build: the app talks to the ephemeral ci backend on localhost:8080.
// production:false is REQUIRED — it enables the ?devPage dev-auth bypass in
// app.component.ts (which the Playwright render relies on).
import { EnvironmentName } from './environment-name';

export const environment = {
  production: false,
  local: true,
  name: 'ci' as EnvironmentName,
  backendApiUrl: 'http://localhost:8080/api',
  rpId: 'localhost',
  // Must match app.dev-tools.key in application-ci.properties.
  devToolsKey: 'ci-local-dev-tools',
  // Empty on purpose: CI renders must never reach a real project.
  posthogKey: '',
  posthogApiHost: ''
};
