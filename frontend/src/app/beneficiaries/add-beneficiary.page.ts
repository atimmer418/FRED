import { Component, DestroyRef, ElementRef, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';
import { NavController, ViewWillLeave } from '@ionic/angular';
import { FormsModule } from '@angular/forms';
import {
  IonHeader,
  IonToolbar,
  IonContent,
  IonIcon,
  ModalController
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { 
  arrowBackOutline,
  saveOutline,
  personOutline,
  calendarOutline,
  mailOutline,
  phonePortraitOutline,
  locationOutline
} from 'ionicons/icons';
import { KeyboardAvoidDirective } from '../directives/keyboard-avoid.directive';
import { BeneficiaryService, Beneficiary } from '../services/beneficiary.service';
import { PasskeyService } from '../services/passkey.service';
import { PinService } from '../services/pin.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { httpStatusOf, isNetworkFailure } from '../services/analytics/analytics-error.utils';
import { AmountTrayHost } from '../components/plan-amount-tray/amount-tray-host';
import { AmountTraySlotComponent } from '../components/plan-amount-tray/amount-tray-slot.component';
import { presentDateSheet } from '../components/fred-date-sheet/fred-date-sheet.component';
import { ageOn, formatFull, formatLong, todayYmd } from '../utils/local-date';
import { PlanAmountScale } from '../utils/plan-amount-scale';

/** The one tray-driven field on this screen. */
type TrayField = 'allocation';
/** Whole percents, 1 to 100; the minus and plus move by one. */
const ALLOCATION_SCALE: PlanAmountScale = { min: 1, max: 100, step: 1, fine: 1 };

@Component({
  selector: 'app-add-beneficiary',
  templateUrl: './add-beneficiary.page.html',
  styleUrls: ['./add-beneficiary.page.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IonHeader, IonToolbar, IonContent,
    IonIcon,
    KeyboardAvoidDirective,
    AmountTraySlotComponent
  ]
})
export class AddBeneficiaryPage implements OnInit, ViewWillLeave {

  /** The allocation field opens the slide tray instead of the keyboard; the value stays on `beneficiary`. */
  readonly tray: AmountTrayHost<TrayField>;

  public beneficiary: Partial<Beneficiary> = {
    beneficiaryType: 'PRIMARY',
    country: 'US'
  };
  private originalBeneficiary: string = '';

  public isLoading = false;
  public editMode = false;
  public beneficiaryId?: number;

  public relationshipOptions = [
    { value: 'SPOUSE', label: 'Spouse' },
    { value: 'CHILD', label: 'Child' },
    { value: 'PARENT', label: 'Parent' },
    { value: 'SIBLING', label: 'Sibling' },
    { value: 'GRANDCHILD', label: 'Grandchild' },
    { value: 'GRANDPARENT', label: 'Grandparent' },
    { value: 'AUNT_UNCLE', label: 'Aunt/Uncle' },
    { value: 'NIECE_NEPHEW', label: 'Niece/Nephew' },
    { value: 'COUSIN', label: 'Cousin' },
    { value: 'FRIEND', label: 'Friend' },
    { value: 'DOMESTIC_PARTNER', label: 'Domestic Partner' },
    { value: 'TRUST', label: 'Trust' },
    { value: 'ESTATE', label: 'Estate' },
    { value: 'CHARITY', label: 'Charity/Organization' },
    { value: 'OTHER', label: 'Other' }
  ];

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    private beneficiaryService: BeneficiaryService,
    private passkeyService: PasskeyService,
    private pinService: PinService,
    private toastService: ToastService,
    private navCtrl: NavController,
    private analytics: AnalyticsService,
    private modalCtrl: ModalController,
    elementRef: ElementRef<HTMLElement>,
    destroyRef: DestroyRef
  ) {
    this.tray = new AmountTrayHost<TrayField>({
      root: elementRef,
      destroyRef,
      fields: {
        allocation: {
          label: 'Allocation Percentage',
          scale: ALLOCATION_SCALE,
          unit: 'percent',
          value: () => this.beneficiary.percentageAllocation ?? 0,
          set: v => { this.beneficiary.percentageAllocation = v; },
        },
      },
    });
    addIcons({
      arrowBackOutline,
      saveOutline,
      personOutline,
      calendarOutline,
      mailOutline,
      phonePortraitOutline,
      locationOutline
    });
  }

  goBack() {
    this.navCtrl.navigateBack('/beneficiaries');
  }

  /** Leaving the page: the tray snaps shut rather than collapsing into a view that is going. */
  ionViewWillLeave(): void {
    this.tray.close(true);
  }

  /** The date of birth opens the FRED date sheet, no later than today. Nothing changes until Done. */
  async openDateOfBirth(): Promise<void> {
    const date = await presentDateSheet(this.modalCtrl, {
      title: 'Date of birth',
      mode: 'birthday',
      value: this.beneficiary.dateOfBirth || null,
      max: todayYmd(),
    });
    if (date !== null) {
      this.beneficiary.dateOfBirth = date;
    }
  }

  /** 'Mar 14, 1991' on the button; empty while nothing is chosen. */
  get dobLabel(): string {
    return this.beneficiary.dateOfBirth ? formatLong(this.beneficiary.dateOfBirth) : '';
  }

  /** '34 years old' under the field, as of today; empty while nothing is chosen. */
  get dobEcho(): string {
    if (!this.beneficiary.dateOfBirth) return '';
    const years = ageOn(this.beneficiary.dateOfBirth, todayYmd());
    return `${years} ${years === 1 ? 'year' : 'years'} old`;
  }

  get dobAriaLabel(): string {
    const value = this.beneficiary.dateOfBirth ? formatFull(this.beneficiary.dateOfBirth) : 'not set';
    return `Date of birth, ${value}. Opens a date picker`;
  }

  ngOnInit() {
    // Check if we're in edit mode
    this.route.params.subscribe(params => {
      if (params['id']) {
        this.editMode = true;
        this.beneficiaryId = parseInt(params['id']);
        this.loadBeneficiaryForEdit();
      }
    });

    // Set default beneficiary type based on route
    const beneficiaryType = this.route.snapshot.queryParams['type'];
    if (beneficiaryType === 'contingent') {
      this.beneficiary.beneficiaryType = 'CONTINGENT';
    }
  }

  private async loadBeneficiaryForEdit() {
    if (!this.beneficiaryId) return;

    try {
      this.isLoading = true;
      // Note: You might need to add a getBeneficiary(id) method to the service
      // For now, we'll load all beneficiaries and find the one we need
      const beneficiaries = await this.beneficiaryService.getBeneficiaries().toPromise();
      const beneficiary = beneficiaries?.find(b => b.id === this.beneficiaryId);
      
      if (beneficiary) {
        this.beneficiary = { ...beneficiary };
        // Fix date parsing - convert date strings to proper format for the input
        if (this.beneficiary.dateOfBirth) {
          this.beneficiary.dateOfBirth = this.parseBackendDate(this.beneficiary.dateOfBirth);
        }
        
        // Store original state for change detection
        this.originalBeneficiary = JSON.stringify(this.beneficiary);
      } else {
        // The read itself succeeded; the row just was not in it, so there is no status to send.
        this.analytics.trackErrorShown('load_failed', 'portfolio', 'toast', null, false);
        this.toastService.showToast('Beneficiary not found', 'danger');
        this.router.navigate(['/beneficiaries']);
      }
    } catch (error) {
      console.error('Error loading beneficiary for edit:', error);
      this.analytics.trackErrorShown(
        isNetworkFailure(error) ? 'network' : 'load_failed',
        'portfolio',
        'toast',
        httpStatusOf(error),
        false,
      );
      this.toastService.showToast('Failed to load beneficiary data', 'danger');
    } finally {
      this.isLoading = false;
    }
  }

  /**
   * Editing a beneficiary whose number the server already holds. The server never sends the number
   * back, only its last four digits, so the field starts empty and may stay empty: leaving it blank
   * keeps the number on file.
   */
  get hasNumberOnFile(): boolean {
    return this.editMode && !!this.beneficiary.ssnLast4;
  }

  get ssnPlaceholder(): string {
    return this.hasNumberOnFile ? `•••-••-${this.beneficiary.ssnLast4}` : 'XXX-XX-XXXX';
  }

  get isFormComplete(): boolean {
    const isComplete = !!(
      this.beneficiary.firstName?.trim() &&
      this.beneficiary.lastName?.trim() &&
      this.beneficiary.dateOfBirth &&
      (this.hasNumberOnFile || this.beneficiary.socialSecurityNumber?.trim()) &&
      this.beneficiary.relationship &&
      this.beneficiary.percentageAllocation && 
      this.beneficiary.percentageAllocation > 0
    );

    if (this.editMode) {
      const hasChanges = JSON.stringify(this.beneficiary) !== this.originalBeneficiary;
      return isComplete && hasChanges;
    }

    return isComplete;
  }

  async onSave() {
    this.tray.close();
    if (!this.validateForm()) {
      // Fired here rather than inside validateForm's nine branches: each one shows the same kind
      // of toast and would produce an identical payload, and the message is what differs, which is
      // the one thing that may not be sent.
      this.analytics.trackErrorShown('validation', 'portfolio', 'toast', null, false);
      return;
    }

    try {
      this.isLoading = true;

      // Step-up authentication check
      const hasPin = await this.pinService.hasPin();

      if (hasPin) {
        const pinVerified = await this.pinService.promptPin('verify');
        if (!pinVerified) {
          this.analytics.trackErrorShown('auth_required', 'portfolio', 'toast', null, false);
          this.toastService.showToast('Authentication required to save beneficiary.', 'warning');
          this.isLoading = false;
          return;
        }
      }

      // Format data for API

      // Clean the beneficiary data to only include fields that should be sent to the server
      const cleanBeneficiary: Beneficiary = {
        beneficiaryType: this.beneficiary.beneficiaryType!,
        firstName: this.beneficiary.firstName!,
        lastName: this.beneficiary.lastName!,
        email: this.beneficiary.email,
        phone: this.beneficiary.phone,
        // Already the backend's YYYY-MM-DD: the sheet returns it and parseBackendDate normalises to it.
        dateOfBirth: this.beneficiary.dateOfBirth,
        // Left out when blank: on an edit that tells the server to keep the number on file.
        socialSecurityNumber: this.beneficiary.socialSecurityNumber?.trim() || undefined,
        addressLine1: this.beneficiary.addressLine1,
        addressLine2: this.beneficiary.addressLine2,
        city: this.beneficiary.city,
        state: this.beneficiary.state,
        postalCode: this.beneficiary.postalCode,
        country: this.beneficiary.country,
        relationship: this.beneficiary.relationship!,
        percentageAllocation: this.beneficiary.percentageAllocation!,
        status: this.beneficiary.status!,
        notes: this.beneficiary.notes
      };

      // Include ID for updates
      if (this.editMode && this.beneficiaryId) {
        cleanBeneficiary.id = this.beneficiaryId;
      }

      if (this.editMode && this.beneficiaryId) {
        // Update existing beneficiary
        await this.beneficiaryService.updateBeneficiary(this.beneficiaryId, cleanBeneficiary).toPromise();
        this.toastService.showToast('Beneficiary updated successfully', 'success');
      } else {
        // Create new beneficiary
        await this.beneficiaryService.createBeneficiary(cleanBeneficiary).toPromise();
        this.toastService.showToast('Beneficiary added successfully', 'success');
      }

      // Navigate back to beneficiaries page immediately
      // The beneficiaries page will auto-refresh when we navigate back
      this.router.navigate(['/beneficiaries']);

    } catch (error: any) {
      console.error('Error saving beneficiary:', error);
      this.analytics.trackErrorShown(
        isNetworkFailure(error) ? 'network' : 'save_failed',
        'portfolio',
        'toast',
        httpStatusOf(error),
        false,
      );
      
      let errorMessage = 'Failed to save beneficiary. Please try again.';
      
      // Extract backend error message
      // Handle the case where error.error is the message string itself (e.g. from Java backend exception)
      // or if it's a JSON object like { error: "...", timestamp: ... }
      let backendMsg = '';
      
      if (typeof error?.error === 'string') {
          // If the error body is just a string
          backendMsg = error.error;
      } else if (error?.error?.error && typeof error.error.error === 'string') {
          // If the error body is an object like { error: "message", ... }
          backendMsg = error.error.error;
      } else if (error?.error?.message) {
          // Standard Spring Boot error structure { message: "...", ... }
          backendMsg = error.error.message;
      } else if (error?.message) {
          // Fallback to error.message
          backendMsg = error.message;
      }
      
      // Check for allocation error
      if (typeof backendMsg === 'string' && backendMsg.includes('Total allocation cannot exceed 100%')) {
        // Extract current total
        // Regex to match "Current total: 100.00%" or similar
        const match = backendMsg.match(/Current total: (\d+(\.\d+)?)%/);
        if (match) {
          const currentTotal = parseFloat(match[1]);
          const remaining = 100 - currentTotal;
          
          if (currentTotal >= 100) {
            errorMessage = 'Your allocation is already at 100%, decrease it first and then add another beneficiary.';
          } else {
            // Format remaining to remove trailing zeros if integer
            const formattedRemaining = Number.isInteger(remaining) ? remaining.toString() : remaining.toFixed(2);
             const formattedTotal = Number.isInteger(currentTotal) ? currentTotal.toString() : currentTotal.toFixed(2);
            errorMessage = `You can only currently allocate ${formattedRemaining}% since you have your total beneficiary allocation already at ${formattedTotal}%.`;
          }
        }
      }
      
      this.toastService.showToast(errorMessage, 'danger');
    } finally {
      this.isLoading = false;
    }
  }

  private validateForm(): boolean {
    // Check required fields
    if (!this.beneficiary.firstName?.trim()) {
      this.toastService.showToast('First name is required', 'danger');
      return false;
    }

    if (!this.beneficiary.lastName?.trim()) {
      this.toastService.showToast('Last name is required', 'danger');
      return false;
    }

    if (!this.beneficiary.dateOfBirth) {
      this.toastService.showToast('Date of birth is required', 'danger');
      return false;
    }

    if (!this.hasNumberOnFile && !this.beneficiary.socialSecurityNumber?.trim()) {
      this.toastService.showToast('Social Security Number is required', 'danger');
      return false;
    }

    if (!this.beneficiary.relationship) {
      this.toastService.showToast('Relationship is required', 'danger');
      return false;
    }

    if (!this.beneficiary.percentageAllocation || this.beneficiary.percentageAllocation <= 0) {
      this.toastService.showToast('Allocation percentage must be greater than 0', 'danger');
      return false;
    }

    if (this.beneficiary.percentageAllocation > 100) {
      this.toastService.showToast('Allocation percentage cannot exceed 100%', 'danger');
      return false;
    }

    // Validate SSN format
    const ssnRegex = /^\d{3}-\d{2}-\d{4}$/;
    if (this.beneficiary.socialSecurityNumber && !ssnRegex.test(this.beneficiary.socialSecurityNumber)) {
      this.toastService.showToast('Social Security Number must be in format XXX-XX-XXXX', 'danger');
      return false;
    }

    // Validate email if provided
    if (this.beneficiary.email && this.beneficiary.email.trim()) {
      const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      if (!emailRegex.test(this.beneficiary.email)) {
        this.toastService.showToast('Please enter a valid email address', 'danger');
        return false;
      }
    }

    return true;
  }

  onCancel() {
    this.router.navigate(['/beneficiaries']);
  }

  /**
   * Parse date from backend - handles both string formats and arrays
   */
  private parseBackendDate(date: any): string {
    if (!date) return '';
    
    // If it's already a string in YYYY-MM-DD format, return as is
    if (typeof date === 'string' && date.match(/^\d{4}-\d{2}-\d{2}$/)) {
      return date;
    }
    
    // If it's an array (e.g., [2025, 11, 16]), convert to YYYY-MM-DD
    if (Array.isArray(date) && date.length >= 3) {
      const year = date[0];
      const month = date[1].toString().padStart(2, '0');
      const day = date[2].toString().padStart(2, '0');
      return `${year}-${month}-${day}`;
    }
    
    // If it's a comma-separated string like "2025,11,16,22,22,13,637928000"
    if (typeof date === 'string' && date.includes(',')) {
      const parts = date.split(',');
      if (parts.length >= 3) {
        const year = parts[0];
        const month = parts[1].padStart(2, '0');
        const day = parts[2].padStart(2, '0');
        return `${year}-${month}-${day}`;
      }
    }
    
    // A date-time string ("2025-11-16T22:22:13"): the calendar day is its first ten characters.
    // Read, not parsed through Date, so the day never shifts with the device's timezone.
    const dateTime = typeof date === 'string' ? /^(\d{4}-\d{2}-\d{2})T/.exec(date) : null;
    if (dateTime) {
      return dateTime[1];
    }

    console.warn('Could not parse date:', date);
    return '';
  }

  formatSSN(event: Event) {
    const raw = (event.target as HTMLInputElement).value;
    let value = raw.replace(/\D/g, '');
    if (value.length >= 6) {
      value = value.replace(/(\d{3})(\d{2})(\d{0,4})/, '$1-$2-$3');
    } else if (value.length >= 4) {
      value = value.replace(/(\d{3})(\d{0,2})/, '$1-$2');
    }
    // Cleared means absent, not '': the edit form compares against what was loaded, and what was
    // loaded has no number at all.
    if (value) {
      this.beneficiary.socialSecurityNumber = value;
    } else {
      delete this.beneficiary.socialSecurityNumber;
    }
    (event.target as HTMLInputElement).value = value;
  }

  formatPhone(event: Event) {
    const raw = (event.target as HTMLInputElement).value;
    let value = raw.replace(/\D/g, '');
    if (value.length >= 7) {
      value = value.replace(/(\d{3})(\d{3})(\d{0,4})/, '($1) $2-$3');
    } else if (value.length >= 4) {
      value = value.replace(/(\d{3})(\d{0,3})/, '($1) $2');
    }
    this.beneficiary.phone = value;
    (event.target as HTMLInputElement).value = value;
  }
}
