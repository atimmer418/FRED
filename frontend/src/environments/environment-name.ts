/**
 * Build identity, shared by every environment file so the type is the union rather than whichever
 * literal the active build happens to use. Without this, `environment.name === 'dev'` is a compile
 * error in a build whose name is 'local'.
 */
export type EnvironmentName = 'local' | 'dev' | 'test' | 'ci' | 'prod';
