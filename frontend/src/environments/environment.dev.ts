export const environment = {
    production: false, // It's a test build, not a full production build
    local: false,
    backendApiUrl: 'https://api-dev.fredvested.com/api',
    rpId: 'potential-engine-97999gqqw9q4hpj7w-8100.app.github.dev', // Your test frontend/app origin hostname
    // Empty on purpose: api-dev is public, so its dev-tools key is never shipped in a build.
    devToolsKey: '',
    posthogKey: '',
    posthogApiHost: 'https://m.fredvested.com'
  };