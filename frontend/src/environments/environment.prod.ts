export const environment = {
  production: true,
  local: false,
  backendApiUrl: 'https://api.fredvested.com/api',
  rpId: 'app.fredvested.com', // Your final production app origin hostname
  devToolsKey: '',
  // Stays empty until a production PostHog project exists. An empty key is a clean no-op.
  posthogKey: '',
  posthogApiHost: 'https://m.fredvested.com'
};