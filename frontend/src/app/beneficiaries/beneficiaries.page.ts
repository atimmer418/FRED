import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import {
  IonHeader,
  IonToolbar,
  IonContent,
  IonIcon,
  IonBadge,
  IonProgressBar,
  IonSpinner,
  IonItemSliding,
  IonItem,
  IonItemOptions,
  IonItemOption,
  ActionSheetController,
  ViewWillEnter
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import { 
  trashOutline,
  peopleOutline,
  addOutline,
  personOutline,
  pricetagOutline, // Using pricetagOutline instead of percentageOutline
  informationCircleOutline,
  checkmarkCircleOutline,
  timeOutline,
  closeCircleOutline,
  arrowBackOutline, 
  shieldCheckmarkOutline, 
  pieChartOutline, 
  personAddOutline, 
  calendarOutline, 
  mailOutline, 
  shieldOutline,
  alertCircleOutline,
  peopleCircleOutline, checkmarkOutline } from 'ionicons/icons';
import { BeneficiaryService, Beneficiary, BeneficiaryAllocationSummary } from '../services/beneficiary.service';
import { ToastService } from '../services/toast.service';
import { AnalyticsService } from '../services/analytics/analytics.service';
import { httpStatusOf, isNetworkFailure } from '../services/analytics/analytics-error.utils';

@Component({
  selector: 'app-beneficiaries',
  templateUrl: './beneficiaries.page.html',
  styleUrls: ['./beneficiaries.page.scss'],
  standalone: true,
  imports: [
    CommonModule,
    IonHeader, IonToolbar, IonContent, IonIcon,
    IonBadge, IonProgressBar, IonSpinner,
    IonItemSliding, IonItem, IonItemOptions, IonItemOption
  ]
})
export class BeneficiariesPage implements OnInit, ViewWillEnter {
  
  public beneficiaries: Beneficiary[] = [];
  public allocationSummary: BeneficiaryAllocationSummary | null = null;
  public isLoading = true;
  public isSubmitting = false;
  
  constructor(
    private router: Router,
    private beneficiaryService: BeneficiaryService,
    private toastService: ToastService,
    private actionSheetCtrl: ActionSheetController,
    private navCtrl: NavController,
    private analytics: AnalyticsService
  ) {
    addIcons({trashOutline, peopleOutline,informationCircleOutline,shieldCheckmarkOutline,pieChartOutline,alertCircleOutline,checkmarkOutline,personAddOutline,checkmarkCircleOutline,addOutline,calendarOutline,mailOutline,shieldOutline,personOutline,pricetagOutline,timeOutline,closeCircleOutline,arrowBackOutline,peopleCircleOutline});
  }
  
  goBack() {
    this.navCtrl.navigateBack('/tabs/tab3');
  }

  ngOnInit() {
    this.loadBeneficiaries();
  }

  ionViewWillEnter() {
    // This will run every time the user navigates to this page
    // including when returning from add/edit beneficiary pages
    this.loadBeneficiaries();
  }
  
  private async loadBeneficiaries() {
    try {
      this.isLoading = true;
      
      // Load beneficiaries and allocation summary from API
      const [beneficiariesResponse, summaryResponse] = await Promise.all([
        this.beneficiaryService.getBeneficiaries().toPromise(),
        this.beneficiaryService.getAllocationSummary().toPromise()
      ]);
      
      this.beneficiaries = beneficiariesResponse || [];
      this.allocationSummary = summaryResponse || null;
      
    } catch (error) {
      console.error('Error loading beneficiaries:', error);
      this.analytics.trackErrorShown(
        isNetworkFailure(error) ? 'network' : 'load_failed',
        'portfolio',
        'toast',
        httpStatusOf(error),
        false,
      );
      this.toastService.showToast('Failed to load beneficiaries. Please check your connection.', 'danger');
      
      // Initialize empty data on error
      this.beneficiaries = [];
      this.allocationSummary = null;
    } finally {
      this.isLoading = false;
    }
  }
  
  onAddBeneficiary(type?: 'primary' | 'contingent') {
    const queryParams = type ? { type } : {};
    this.router.navigate(['/beneficiaries/add'], { queryParams });
  }
  
  onEditBeneficiary(beneficiary: Beneficiary) {
    this.router.navigate(['/beneficiaries/edit', beneficiary.id]);
  }
  
  getStatusColor(status: string): string {
    switch (status) {
      case 'APPROVED': return 'success';
      case 'PENDING': return 'warning';
      case 'SUBMITTED': return 'primary';
      case 'REJECTED': return 'danger';
      case 'INACTIVE': return 'medium';
      default: return 'medium';
    }
  }
  
  getStatusIcon(status: string): string {
    switch (status) {
      case 'APPROVED': return 'checkmark-circle-outline';
      case 'PENDING': return 'time-outline';
      case 'SUBMITTED': return 'information-circle-outline';
      case 'REJECTED': return 'close-circle-outline';
      case 'INACTIVE': return 'close-circle-outline';
      default: return 'information-circle-outline';
    }
  }
  
  getRelationshipDisplayName(relationship: string): string {
    const relationshipMap: { [key: string]: string } = {
      'SPOUSE': 'Spouse',
      'CHILD': 'Child',
      'PARENT': 'Parent',
      'SIBLING': 'Sibling',
      'GRANDCHILD': 'Grandchild',
      'GRANDPARENT': 'Grandparent',
      'AUNT_UNCLE': 'Aunt/Uncle',
      'NIECE_NEPHEW': 'Niece/Nephew',
      'COUSIN': 'Cousin',
      'FRIEND': 'Friend',
      'DOMESTIC_PARTNER': 'Domestic Partner',
      'TRUST': 'Trust',
      'ESTATE': 'Estate',
      'CHARITY': 'Charity/Organization',
      'OTHER': 'Other'
    };
    return relationshipMap[relationship] || relationship;
  }
  
  getPrimaryBeneficiaries(): Beneficiary[] {
    return this.beneficiaries.filter(b => b.beneficiaryType === 'PRIMARY' && b.status !== 'INACTIVE');
  }
  
  getContingentBeneficiaries(): Beneficiary[] {
    return this.beneficiaries.filter(b => b.beneficiaryType === 'CONTINGENT' && b.status !== 'INACTIVE');
  }
  
  getPendingBeneficiaries(): Beneficiary[] {
    return this.beneficiaries.filter(b => b.status === 'PENDING');
  }
  
  getApprovedBeneficiaries(): Beneficiary[] {
    return this.beneficiaries.filter(b => b.status === 'APPROVED');
  }

  /**
   * Check if beneficiary setup is truly complete:
   * - Primary allocation must be 100%
   * - All primary beneficiaries must be approved (not just pending)
   */
  isBeneficiarySetupComplete(): boolean {
    const isPrimaryAllocationComplete = this.allocationSummary?.primaryAllocationComplete ?? false;
    const primaryBeneficiaries = this.getPrimaryBeneficiaries();
    const allPrimaryApproved = primaryBeneficiaries.length > 0 && 
      primaryBeneficiaries.every(b => b.status === 'APPROVED');
    
    return isPrimaryAllocationComplete && allPrimaryApproved;
  }
  
  /**
   * Submit a specific beneficiary to Alpaca
   */
  async onSubmitBeneficiary(beneficiary: Beneficiary) {
    if (!beneficiary.id) {
      this.analytics.trackErrorShown('validation', 'portfolio', 'toast', null, false);
      this.toastService.showToast('Cannot submit beneficiary: Invalid ID', 'danger');
      return;
    }
    
    try {
      // Show submission loading state
      this.isSubmitting = true;
      
      await this.beneficiaryService.submitBeneficiary(beneficiary.id).toPromise();
      this.toastService.showToast(`Successfully submitted ${beneficiary.firstName} ${beneficiary.lastName} to Alpaca`, 'success');
      
      // Refresh the data to show updated status
      await this.loadBeneficiaries();
      
    } catch (error: any) {
      console.error('Error submitting beneficiary:', error);
      this.analytics.trackErrorShown(
        isNetworkFailure(error) ? 'network' : 'save_failed',
        'portfolio',
        'toast',
        httpStatusOf(error),
        false,
      );
      
      // Show more detailed error information
      let errorMessage = 'Failed to submit beneficiary. Please try again.';
      if (error?.error?.message) {
        errorMessage = `Submission failed: ${error.error.message}`;
      } else if (error?.message) {
        errorMessage = `Submission failed: ${error.message}`;
      }
      
      this.toastService.showToast(errorMessage, 'danger');
    } finally {
      // Clear submission loading state
      this.isSubmitting = false;
    }
  }
  
  /**
   * Submit all pending beneficiaries to Alpaca
   */
  async onSubmitAllBeneficiaries() {
    const pendingCount = this.getPendingBeneficiaries().length;
    
    if (pendingCount === 0) {
      this.analytics.trackErrorShown('validation', 'portfolio', 'toast', null, false);
      this.toastService.showToast('No pending beneficiaries to submit', 'warning');
      return;
    }
    
    try {
      // Show submission loading state
      this.isSubmitting = true;
      
      await this.beneficiaryService.submitAllBeneficiaries().toPromise();
      this.toastService.showToast(`Successfully submitted ${pendingCount} beneficiar${pendingCount === 1 ? 'y' : 'ies'} to Alpaca`, 'success');
      
      // Refresh the data to show updated status
      await this.loadBeneficiaries();
      
    } catch (error: any) {
      console.error('Error submitting all beneficiaries:', error);
      this.analytics.trackErrorShown(
        isNetworkFailure(error) ? 'network' : 'save_failed',
        'portfolio',
        'toast',
        httpStatusOf(error),
        false,
      );
      
      // Show more detailed error information  
      let errorMessage = 'Failed to submit beneficiaries. Please try again.';
      if (error?.error?.message) {
        errorMessage = `Submission failed: ${error.error.message}`;
      } else if (error?.message) {
        errorMessage = `Submission failed: ${error.message}`;
      }
      
      this.toastService.showToast(errorMessage, 'danger');
    } finally {
      // Clear submission loading state
      this.isSubmitting = false;
    }
  }
  
  /**
   * Delete a beneficiary with confirmation
   */
  async onDeleteBeneficiary(beneficiary: Beneficiary) {
    if (!beneficiary.id) {
      this.analytics.trackErrorShown('validation', 'portfolio', 'toast', null, false);
      this.toastService.showToast('Cannot delete beneficiary: Invalid ID', 'danger');
      return;
    }
    
    const actionSheet = await this.actionSheetCtrl.create({
      header: 'Delete Beneficiary?',
      subHeader: 'This beneficiary will be deleted.',
      buttons: [
        {
          text: 'Delete',
          role: 'destructive',
          data: {
            action: 'delete',
          },
          handler: async () => {
            try {
              // Delete (mark inactive) in backend - this processes the inactive status update
              await this.beneficiaryService.deleteBeneficiary(beneficiary.id!).toPromise();
              
              this.toastService.showToast(`Deleted ${beneficiary.firstName} ${beneficiary.lastName} and updated Alpaca`, 'success');
              
              // Refresh the data to show updated list
              await this.loadBeneficiaries();
              
            } catch (error) {
              console.error('Error deleting beneficiary:', error);
              this.analytics.trackErrorShown(
                isNetworkFailure(error) ? 'network' : 'save_failed',
                'portfolio',
                'toast',
                httpStatusOf(error),
                false,
              );
              this.toastService.showToast('Failed to delete beneficiary. Please try again.', 'danger');
            }
          }
        },
        {
          text: 'Cancel',
          role: 'cancel',
          data: {
            action: 'cancel',
          },
        },
      ],
    });

    await actionSheet.present();
  }
  
  /**
   * Refresh beneficiaries data from the server
   */
  async refreshData() {
    await this.loadBeneficiaries();
  }

  /**
   * Convert date to a valid Date object for the date pipe
   */
  formatDate(dateValue: any): Date | null {
    if (!dateValue) return null;
    
    // If it's already a Date object, return it
    if (dateValue instanceof Date) return dateValue;
    
    // If it's a string in format "2002,4,18" or similar, convert it
    if (typeof dateValue === 'string') {
      // Handle comma-separated format like "2002,4,18"
      if (dateValue.includes(',')) {
        const parts = dateValue.split(',').map(p => parseInt(p.trim()));
        if (parts.length === 3) {
          // Create date: year, month (0-based), day
          return new Date(parts[0], parts[1] - 1, parts[2]);
        }
      }
      
      // Try parsing as ISO string or other standard format
      const parsed = new Date(dateValue);
      return isNaN(parsed.getTime()) ? null : parsed;
    }
    
    // For any other type, try to convert to Date
    const parsed = new Date(dateValue);
    return isNaN(parsed.getTime()) ? null : parsed;
  }

  /**
   * Format date for display without timezone issues
   * Returns a formatted string directly without Date conversion
   */
  formatDateForDisplay(dateValue: any): string {
    if (!dateValue) return '';
    
    // If it's a string in YYYY-MM-DD format, format it nicely
    if (typeof dateValue === 'string' && dateValue.match(/^\d{4}-\d{2}-\d{2}$/)) {
      const [year, month, day] = dateValue.split('-');
      const monthNames = ['January', 'February', 'March', 'April', 'May', 'June',
        'July', 'August', 'September', 'October', 'November', 'December'];
      const monthName = monthNames[parseInt(month) - 1];
      return `${monthName} ${parseInt(day)}, ${year}`;
    }
    
    // If it's a comma-separated format like "2002,4,18"
    if (typeof dateValue === 'string' && dateValue.includes(',')) {
      const parts = dateValue.split(',').map(p => parseInt(p.trim()));
      if (parts.length >= 3) {
        const monthNames = ['January', 'February', 'March', 'April', 'May', 'June',
          'July', 'August', 'September', 'October', 'November', 'December'];
        const monthName = monthNames[parts[1] - 1];
        return `${monthName} ${parts[2]}, ${parts[0]}`;
      }
    }
    
    // If it's an array like [2002, 4, 18]
    if (Array.isArray(dateValue) && dateValue.length >= 3) {
      const monthNames = ['January', 'February', 'March', 'April', 'May', 'June',
        'July', 'August', 'September', 'October', 'November', 'December'];
      const monthName = monthNames[dateValue[1] - 1];
      return `${monthName} ${dateValue[2]}, ${dateValue[0]}`;
    }
    
    // Fallback
    return dateValue.toString();
  }
}
