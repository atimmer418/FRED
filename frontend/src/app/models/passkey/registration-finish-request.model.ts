export interface RegistrationFinishRequest {
    email: string;
    // This will be the JSON result from navigator.credentials.create()
    credential: any;
    // Only after account recovery: the one-time token from /auth/recovery/verify.
    recoveryToken?: string;
}