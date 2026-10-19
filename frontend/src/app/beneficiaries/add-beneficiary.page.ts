import { Component, OnInit } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';
import { NavController } from '@ionic/angular';
import { FormsModule } from '@angular/forms';
import {
  IonHeader,
  IonToolbar,
  IonContent,
  IonButton,
  IonIcon,
  IonButtons,
  IonModal,
  IonDatetime
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

@Component({
  selector: 'app-add-beneficiary',
  templateUrl: './add-beneficiary.page.html',
  styleUrls: ['./add-beneficiary.page.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IonHeader, IonToolbar, IonContent, IonButton,
    IonIcon, IonButtons,
    IonModal, IonDatetime,
    KeyboardAvoidDirective
  ],
  providers: [DatePipe]
})
export class AddBeneficiaryPage implements OnInit {
  
  public beneficiary: Partial<Beneficiary> = {
    beneficiaryType: 'PRIMARY',
    country: 'US'
  };
  private originalBeneficiary: string = '';
  
  public today = new Date().toISOString();
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
    private analytics: AnalyticsService
  ) {
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

  get isFormComplete(): boolean {
    const isComplete = !!(
      this.beneficiary.firstName?.trim() &&
      this.beneficiary.lastName?.trim() &&
      this.beneficiary.dateOfBirth &&
      this.beneficiary.socialSecurityNumber?.trim() &&
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
        dateOfBirth: this.formatDateForBackend(this.beneficiary.dateOfBirth),
        socialSecurityNumber: this.beneficiary.socialSecurityNumber,
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

    if (!this.beneficiary.socialSecurityNumber?.trim()) {
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
    
    // Try to parse as a Date object
    try {
      const parsedDate = new Date(date);
      if (!isNaN(parsedDate.getTime())) {
        return parsedDate.toISOString().split('T')[0]; // YYYY-MM-DD format
      }
    } catch (e) {
      console.warn('Could not parse date:', date);
    }
    
    return '';
  }

  /**
   * Format date for backend submission
   * Ensures the date is in YYYY-MM-DD format
   */
  private formatDateForBackend(dateValue: any): string | undefined {
    if (!dateValue) return undefined;
    
    // If it's already a string in YYYY-MM-DD format, return as is
    if (typeof dateValue === 'string') {
      // Check if it's already in YYYY-MM-DD format
      if (/^\d{4}-\d{2}-\d{2}$/.test(dateValue)) {
        return dateValue;
      }
      
      // Try to parse and reformat if it's in a different string format
      const date = new Date(dateValue);
      if (!isNaN(date.getTime())) {
        return date.toISOString().split('T')[0]; // YYYY-MM-DD
      }
    }
    
    // If it's a Date object, format it
    if (dateValue instanceof Date) {
      return dateValue.toISOString().split('T')[0]; // YYYY-MM-DD
    }
    
    return undefined;
  }

  formatSSN(event: Event) {
    const raw = (event.target as HTMLInputElement).value;
    let value = raw.replace(/\D/g, '');
    if (value.length >= 6) {
      value = value.replace(/(\d{3})(\d{2})(\d{0,4})/, '$1-$2-$3');
    } else if (value.length >= 4) {
      value = value.replace(/(\d{3})(\d{0,2})/, '$1-$2');
    }
    this.beneficiary.socialSecurityNumber = value;
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
