import { EnvironmentName } from './environment-name';

export const environment = {
    production: false, // It's a test build, not a full production build
    local: false,
    name: 'test' as EnvironmentName,
    backendApiUrl: 'https://api-test.fredvested.com/api',
    rpId: 'api-test.fredvested.com', // Your test frontend/app origin hostname
    devToolsKey: '',
    posthogKey: '',
    posthogApiHost: 'https://m.fredvested.com'
  };