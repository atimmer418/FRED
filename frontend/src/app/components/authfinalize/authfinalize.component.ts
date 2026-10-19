import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  IonHeader, IonToolbar, IonContent, IonInput,
  IonSpinner, IonFooter, NavController
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { personCircleOutline, helpCircleOutline, lockClosed } from 'ionicons/icons';
import { environment } from '../../../environments/environment';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';

// --- NEW IMPORTS ---
import { PasskeyService } from '../../services/passkey.service';
import { AuthService } from '../../services/auth.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { JwtTokenUtils } from '../../utils/jwt-token.utils';
import { create } from '@github/webauthn-json';

@Component({
  selector: 'app-authfinalize',
  templateUrl: './authfinalize.component.html',
  styleUrls: ['./authfinalize.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule, ReactiveFormsModule, RouterLink,
    IonHeader, IonToolbar, IonContent, IonInput,
    IonSpinner, IonFooter,
    KeyboardAvoidDirective
  ]
})
export class AuthFinalizeComponent implements OnInit, OnDestroy {
  registerForm: FormGroup;
  isLoading: boolean = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  hasRequiredParams: boolean = false;

  planId: string | null = null;
  timeToFI: string | null = null;
  targetPortfolio: number | null = null;
  retirementIncome: number | null = null;
  monthlyInvestment: number | null = null;

  // for work development
  simulatePasskey: boolean = false; // Toggle this to simulate passkey creation

  emailTouched: boolean = false;
  protected env = environment;

  private routeSub: Subscription | undefined;

  /** True when the screen had to rebuild its plan data from storage rather than the query params. */
  private resumedFromSavedData = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private navCtrl: NavController,
    private passkeyService: PasskeyService,
    private authService: AuthService,
    private analytics: AnalyticsService,
  ) {
    addIcons({lockClosed,helpCircleOutline,personCircleOutline});
    this.registerForm = new FormGroup({
      email: new FormControl('', [Validators.email, Validators.maxLength(100)]),
    });
  }

  ngOnInit() {
    // Make component accessible from browser console for debugging
    (window as any)['authFinalizeComponent'] = this;

    this.routeSub = this.route.queryParamMap.subscribe(params => {
      const pParam = params.get('p');
      const rIParam = params.get('rI');
      const mIParam = params.get('mI');

      // First try to get values from query parameters (fresh navigation from fi-plan-results)
      this.planId = params.get('plan');
      this.timeToFI = params.get('t');
      this.targetPortfolio = pParam !== null ? +pParam : null;
      this.retirementIncome = rIParam !== null ? +rIParam : null;
      this.monthlyInvestment = mIParam !== null ? +mIParam : null;

      // If query parameters are missing, try to load from localStorage
      if (!this.planId || !this.timeToFI || !this.targetPortfolio || !this.retirementIncome || !this.monthlyInvestment) {
        console.log('Auth-finalize query parameters missing, trying to load from saved data');
        this.loadSavedData();
      }

      // Save current values to localStorage for future navigation
      if (this.planId && this.timeToFI && this.targetPortfolio && this.retirementIncome && this.monthlyInvestment) {
        this.saveDataToLocalStorage();
      }

      // Validate after loading from both query params and localStorage
      this.validateRequiredParams();
    });

    // queryParamMap emits synchronously on subscribe, so the resumed flag is already settled.
    this.analytics.trackOnboardingStepViewed('auth_finalize', null, this.resumedFromSavedData);
  }

  private validateRequiredParams() {
    if (this.planId && this.timeToFI && this.targetPortfolio && this.retirementIncome && this.monthlyInvestment) {
      console.log('Auth-finalize: All required parameters available:', {
        planId: this.planId,
        timeToFI: this.timeToFI,
        targetPortfolio: this.targetPortfolio,
        retirementIncome: this.retirementIncome,
        monthlyInvestment: this.monthlyInvestment
      });

      this.hasRequiredParams = true;
      this.errorMessage = null; // Clear any previous error
    } else {
      console.warn('Auth-finalize: Some required parameters are missing:', {
        planId: this.planId,
        timeToFI: this.timeToFI,
        targetPortfolio: this.targetPortfolio,
        retirementIncome: this.retirementIncome,
        monthlyInvestment: this.monthlyInvestment
      });
      this.errorMessage = 'Some required parameters are missing. Please restart from the time to FI page.';
      this.hasRequiredParams = false;
    }
  }
  
  private loadSavedData() {
    // Snapshot first: 'resumed' means storage actually filled something in, not merely that this
    // method ran (it also runs when only one query param is missing).
    const beforeLoad = this.planDataFingerprint();

    // Try to get from localStorage - check multiple sources for each piece of data
    const savedPlan = localStorage.getItem('fiPlanSelectedStrategy');
    const savedTimeToFI = localStorage.getItem('fiPlanTimeToFI');
    const savedTargetPortfolio = localStorage.getItem('fiPlanTargetPortfolio');
    const savedRetirement = localStorage.getItem('surveyRetirementIncome');
    const savedMonthly = localStorage.getItem('surveyMonthlyInvestment');
    
    // Also try to load from the auth-finalize specific storage
    const authFinalizeData = localStorage.getItem('authFinalizeData');
    let savedAuthData = null;
    if (authFinalizeData) {
      try {
        savedAuthData = JSON.parse(authFinalizeData);
      } catch (e) {
        console.warn('Failed to parse authFinalizeData from localStorage:', e);
      }
    }
    
    // Load each parameter with fallback priority: current -> localStorage -> authFinalizeData
    if (!this.planId) {
      this.planId = savedPlan || savedAuthData?.planId || null;
    }
    if (!this.timeToFI) {
      this.timeToFI = savedTimeToFI || savedAuthData?.timeToFI || null;
    }
    if (!this.targetPortfolio) {
      this.targetPortfolio = savedTargetPortfolio ? parseFloat(savedTargetPortfolio) : 
                            (savedAuthData?.targetPortfolio || null);
    }
    if (!this.retirementIncome) {
      this.retirementIncome = savedRetirement ? parseInt(savedRetirement, 10) : 
                             (savedAuthData?.retirementIncome || null);
    }
    if (!this.monthlyInvestment) {
      this.monthlyInvestment = savedMonthly ? parseInt(savedMonthly, 10) : 
                              (savedAuthData?.monthlyInvestment || null);
    }
    
    if (this.planDataFingerprint() !== beforeLoad) {
      this.resumedFromSavedData = true;
    }

    console.log('Auth-finalize loaded saved data from localStorage:', {
      planId: this.planId,
      timeToFI: this.timeToFI,
      targetPortfolio: this.targetPortfolio,
      retirementIncome: this.retirementIncome,
      monthlyInvestment: this.monthlyInvestment,
      sources: {
        plan: savedPlan ? 'fiPlanSelectedStrategy' : (savedAuthData?.planId ? 'authFinalizeData' : 'none'),
        timeToFI: savedTimeToFI ? 'fiPlanTimeToFI' : (savedAuthData?.timeToFI ? 'authFinalizeData' : 'none'),
        targetPortfolio: savedTargetPortfolio ? 'fiPlanTargetPortfolio' : (savedAuthData?.targetPortfolio ? 'authFinalizeData' : 'none'),
        retirementIncome: savedRetirement ? 'surveyRetirementIncome' : (savedAuthData?.retirementIncome ? 'authFinalizeData' : 'none'),
        monthlyInvestment: savedMonthly ? 'surveyMonthlyInvestment' : (savedAuthData?.monthlyInvestment ? 'authFinalizeData' : 'none')
      }
    });
  }
  
  /** Cheap equality check over the five plan fields, used only to detect a storage-driven refill. */
  private planDataFingerprint(): string {
    return [this.planId, this.timeToFI, this.targetPortfolio, this.retirementIncome, this.monthlyInvestment].join('|');
  }

  private saveDataToLocalStorage() {
    localStorage.setItem('authFinalizeData', JSON.stringify({
      planId: this.planId,
      timeToFI: this.timeToFI,
      targetPortfolio: this.targetPortfolio,
      retirementIncome: this.retirementIncome,
      monthlyInvestment: this.monthlyInvestment
    }));
    console.log('Auth-finalize saved data to localStorage');
  }

  get email() { return this.registerForm.get('email'); }

  async bypassAuthID() {
    this.simulatePasskey = true;
    await this.createPasskey();
  }

  bufferToBase64url(buffer: ArrayBuffer): string {
    const bytes = new Uint8Array(buffer);
    let str = '';
    for (let i = 0; i < bytes.byteLength; i++) {
      str += String.fromCharCode(bytes[i]);
    }
    return btoa(str)
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
  }

  // --- REFACTORED METHOD ---
  async createPasskey() {
    this.errorMessage = null;
    this.successMessage = null;

    if (this.registerForm.invalid) {
      this.registerForm.markAllAsTouched();
      this.errorMessage = 'Please provide a valid email address.';
      return;
    }
    if (!this.hasRequiredParams) {
      this.errorMessage = "Cannot proceed without a valid session. Please start from time to FI page again.";
      return;
    }

    // Diagnostic logging — check WKWebView passkey environment
    let passkeySupported = false;
    try {
      const fn = (window.PublicKeyCredential as any)?.isUserVerifyingPlatformAuthenticatorAvailable;
      passkeySupported = fn ? await fn.call(window.PublicKeyCredential) : false;
    } catch (e) {
      console.warn('[PASSKEY_DIAG] isUserVerifyingPlatformAuthenticatorAvailable threw:', e);
      passkeySupported = false;
    }
    console.log('[PASSKEY_DIAG] origin:', window.location.origin);
    console.log('[PASSKEY_DIAG] isSecureContext:', window.isSecureContext);
    console.log('[PASSKEY_DIAG] PublicKeyCredential defined:', typeof (window as any).PublicKeyCredential);
    console.log('[PASSKEY_DIAG] isUserVerifyingPlatformAuthenticatorAvailable:', passkeySupported);

    this.isLoading = true;
    const userEmail = this.email?.value;

    // STEP 1: Start Registration (Get Challenge from Backend)
    this.passkeyService.startRegistration({
      email: userEmail, 
      planId: this.planId!,
      timeToFI: this.timeToFI!,
      targetPortfolio: this.targetPortfolio!,
      retirementIncome: this.retirementIncome!,
      monthlyInvestment: this.monthlyInvestment!
    }).subscribe({
        next: async (startResponse) => {
          try {
            console.log('Received registration options from server:', startResponse.options);
            if (this.simulatePasskey) {
              console.warn('SIMULATED PASSKEY MODE ENABLED');

              // Simulate a fake credential response
              const fakeCredential = {
                id: 'fake-id',
                rawId: this.bufferToBase64url(Uint8Array.from([1, 2, 3, 4]).buffer),
                type: 'public-key',
                response: {
                  attestationObject: "SIMULATED_ATTESTATION",
                  clientDataJSON: this.bufferToBase64url(Uint8Array.from([9, 10, 11, 12]).buffer)
                },
                clientExtensionResults: {}
              };

              // STEP 3: Finish Registration (simulate)
              this.passkeyService.finishRegistration({ email: userEmail, credential: fakeCredential })
                .subscribe({
                  next: (response) => {
                    if (response.success && response.jwtToken) {
                      console.log('SIMULATED registration and login successful!', response);

                      // The step finishes here, not at the tap: the OS passkey sheet sits between
                      // the two and can be abandoned invisibly, which is the drop worth seeing.
                      this.analytics.trackOnboardingStepCompleted('auth_finalize', null);

                      // Store JWT first so completeStep can authenticate against the backend.
                      // We call handleSuccessfulAuthentication AFTER completeStep so that when
                      // loadUserProgress fires, the DB already has authFinalizeCompleted=true.
                      // Without this order, the GET resolves before the PUT and navigation
                      // routes back to /auth-finalize.
                      JwtTokenUtils.storeJwtToken(response.jwtToken!, response.userId || 0, userEmail);

                      this.authService.completeStep('authFinalize').subscribe({
                        next: () => {
                          console.log('AuthFinalize step completed successfully');
                          // Do NOT clear localStorage flags before handleSuccessfulAuthentication.
                          // hasLocalProgress=true triggers syncLocalStorageProgressToDatabase,
                          // which gives a second DB update path and delays loadUserProgress
                          // until AFTER the sync PUT completes — reducing the race window.
                          this.authService.handleSuccessfulAuthentication(response.jwtToken!, response.userId || 0, userEmail);
                          // Clear flags after handleSuccessfulAuthentication has already read
                          // them for the sync (clearing here is safe since the sync PUT body
                          // was already constructed synchronously inside handleSuccessfulAuthentication).
                          this.authService.clearLocalStorageProgressFlags();
                        },
                        error: (err) => {
                          console.error('Failed to complete AuthFinalize step:', err);
                          this.authService.handleSuccessfulAuthentication(response.jwtToken!, response.userId || 0, userEmail);
                          this.authService.clearLocalStorageProgressFlags();
                        }
                      });
                    } else {
                      this.errorMessage = response.message || 'Registration failed or login did not occur.';
                      console.error('Registration finish response error:', response.message);
                    }
                  },
                  error: (err) => {
                    this.errorMessage = err.error?.message || err.message || 'An unknown error occurred during simulated finish.';
                    console.error('Error finishing simulated registration:', err);
                  }
                });

              return; // ✅ early return — skip the real WebAuthn
            }

            // The backend sends a JSON *string*. The 'create' function needs a JavaScript *object*.
            // We must parse the string from the server before using it.
            const optionsAsObject = JSON.parse(startResponse.options);

            const credentialRequestOptions = {
              publicKey: optionsAsObject
            };

            // STEP 2: Create Credential (Browser/OS Interaction)
            // Pass the new, correctly structured object.
            const credential = await create(credentialRequestOptions);

            // STEP 3: Finish Registration (Send Credential to Backend)
            this.passkeyService.finishRegistration({ email: userEmail, credential })
              .subscribe({
                next: (response) => {
                  if (response.success && response.jwtToken) {
                    console.log('Registration and login successful!', response);

                    // The step finishes here, not at the tap: the OS passkey sheet sits between
                    // the two and can be abandoned invisibly, which is the drop worth seeing.
                    this.analytics.trackOnboardingStepCompleted('auth_finalize', null);

                    // Store JWT first so completeStep can authenticate against the backend.
                    // We call handleSuccessfulAuthentication AFTER completeStep so that when
                    // loadUserProgress fires, the DB already has authFinalizeCompleted=true.
                    // Without this order, the GET resolves before the PUT and navigation
                    // routes back to /auth-finalize.
                    JwtTokenUtils.storeJwtToken(response.jwtToken!, response.userId || 0, userEmail);

                    this.authService.completeStep('authFinalize').subscribe({
                      next: () => {
                        console.log('AuthFinalize step completed successfully');
                        this.authService.handleSuccessfulAuthentication(response.jwtToken!, response.userId || 0, userEmail);
                        this.authService.clearLocalStorageProgressFlags();
                      },
                      error: (err) => {
                        console.error('Failed to complete AuthFinalize step:', err);
                        this.authService.handleSuccessfulAuthentication(response.jwtToken!, response.userId || 0, userEmail);
                        this.authService.clearLocalStorageProgressFlags();
                      }
                    });
                  } else {
                    // Handle cases where registration might be successful but no JWT (shouldn't happen with current backend logic)
                    // Or if success is false
                    this.errorMessage = response.message || 'Registration failed or login did not occur.';
                    console.error('Registration finish response error:', response.message);
                  }
                },
                error: (err) => {
                  this.errorMessage = err.error?.message || err.message || 'An unknown error occurred during registration finish.';
                  console.error('Error finishing passkey registration:', err);
                }
              });

          } catch (error: any) {
            this.isLoading = false;
            // Handle errors from the browser's credential creation (e.g., user cancels)
            this.errorMessage = `Passkey creation was cancelled or failed.`;
            // Category only. The message itself never leaves the device.
            this.analytics.trackErrorShown('passkey', 'onboarding', 'inline_banner', null, true);
            console.error('Error during navigator.credentials.create():', error);
          }
        },
        error: (err) => {
          this.isLoading = false;
          this.errorMessage = `Could not start registration: ${err.error || 'Server error.'}`;
          console.error('Error starting registration:', err);
        }
      });
  }

  recoverAccount() {
    this.router.navigate(['/recovery']);
  }

  goBack() {
    this.navCtrl.navigateBack('/fi-plan-results');
  }

  get isEmailValid(): boolean {
    const val = this.email?.value || '';
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(val);
  }

  ngOnDestroy() {
    if (this.routeSub) this.routeSub.unsubscribe();
  }

  /**
   * Attempt to reload missing parameters from localStorage
   * This is called when user clicks "Try Loading Saved Data" button
   */
  debugMissingParams() {
    console.log('=== AUTH FINALIZE DEBUG INFO ===');
    console.log('Current URL:', window.location.href);
    console.log('Query Params:', this.route.snapshot.queryParams);
    console.log('Current Component State:', {
      planId: this.planId,
      timeToFI: this.timeToFI,
      targetPortfolio: this.targetPortfolio,
      retirementIncome: this.retirementIncome,
      monthlyInvestment: this.monthlyInvestment,
      hasRequiredParams: this.hasRequiredParams,
      errorMessage: this.errorMessage
    });
    
    console.log('LocalStorage Data:');
    console.log('- fiPlanSelectedStrategy:', localStorage.getItem('fiPlanSelectedStrategy'));
    console.log('- fiPlanTimeToFI:', localStorage.getItem('fiPlanTimeToFI'));
    console.log('- fiPlanTargetPortfolio:', localStorage.getItem('fiPlanTargetPortfolio'));
    console.log('- surveyRetirementIncome:', localStorage.getItem('surveyRetirementIncome'));
    console.log('- surveyMonthlyInvestment:', localStorage.getItem('surveyMonthlyInvestment'));
    console.log('- authFinalizeData:', localStorage.getItem('authFinalizeData'));
    
    console.log('=== END DEBUG INFO ===');
    
    // Try to reload data and re-validate
    console.log('Attempting to reload data...');
    
    // Clear current error message while we try to load
    this.errorMessage = null;
    
    // Store values before reload attempt
    const beforeState = {
      planId: this.planId,
      timeToFI: this.timeToFI,
      targetPortfolio: this.targetPortfolio,
      retirementIncome: this.retirementIncome,
      monthlyInvestment: this.monthlyInvestment
    };
    
    this.loadSavedData();
    this.validateRequiredParams();
    
    // Check if anything was loaded
    const afterState = {
      planId: this.planId,
      timeToFI: this.timeToFI,
      targetPortfolio: this.targetPortfolio,
      retirementIncome: this.retirementIncome,
      monthlyInvestment: this.monthlyInvestment
    };
    
    const dataWasLoaded = JSON.stringify(beforeState) !== JSON.stringify(afterState);
    
    if (this.hasRequiredParams) {
      this.successMessage = 'Success! Found your saved plan data.';
      setTimeout(() => {
        this.successMessage = null;
      }, 3000);
    } else if (dataWasLoaded) {
      this.errorMessage = 'Found some saved data, but still missing required information. Please restart from plan selection.';
    } else {
      this.errorMessage = 'No saved plan data found. Please complete the investment survey first.';
    }
  }
}