import { Component, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';
import {
  IonHeader, IonToolbar, IonContent, IonFooter, IonSpinner, NavController
} from '@ionic/angular/standalone';
import { AlpacaService } from '../../services/alpaca.service';
import { AccountStatusService } from '../../services/account-status.service';
import { ToastService } from '../../services/toast.service';

interface DocumentTypeOption {
  value: string;
  label: string;
}

const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

@Component({
  selector: 'app-document-upload',
  templateUrl: './document-upload.component.html',
  styleUrls: ['./document-upload.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    IonHeader, IonToolbar, IonContent, IonFooter, IonSpinner
  ]
})
export class DocumentUploadComponent implements OnDestroy {
  private destroy$ = new Subject<void>();

  isLoading = false;
  selectedDocumentType = '';
  selectedFile: File | null = null;
  filePreviewUrl: string | null = null;
  fileError: string | null = null;

  readonly documentTypes: DocumentTypeOption[] = [
    { value: 'identity_verification', label: 'Government-issued ID (Passport, Driver\'s License)' },
    { value: 'address_verification', label: 'Proof of Address (Utility bill, Bank statement)' },
    { value: 'date_of_birth_verification', label: 'Date of Birth Verification' },
    { value: 'tax_id_verification', label: 'Tax ID / SSN Verification' }
  ];

  constructor(
    private router: Router,
    private navCtrl: NavController,
    private alpacaService: AlpacaService,
    private accountStatusService: AccountStatusService,
    private toastService: ToastService
  ) {}

  get canUpload(): boolean {
    return !!(this.selectedDocumentType && this.selectedFile && !this.fileError);
  }

  onDocumentTypeChange(event: Event) {
    const select = event.target as HTMLSelectElement;
    this.selectedDocumentType = select.value;
  }

  triggerFilePicker() {
    const input = document.getElementById('file-input') as HTMLInputElement;
    input?.click();
  }

  onFileSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    this.fileError = null;
    this.selectedFile = null;
    this.filePreviewUrl = null;

    if (file.size > MAX_FILE_SIZE_BYTES) {
      this.fileError = 'File exceeds 10 MB limit. Please choose a smaller file.';
      return;
    }

    this.selectedFile = file;

    // Generate preview for images
    if (file.type.startsWith('image/')) {
      const reader = new FileReader();
      reader.onload = (e) => {
        this.filePreviewUrl = e.target?.result as string;
      };
      reader.readAsDataURL(file);
    }
  }

  async uploadDocument() {
    if (!this.canUpload || !this.selectedFile) return;

    this.isLoading = true;

    try {
      const base64Content = await this.fileToBase64(this.selectedFile);

      // Strip the data URI prefix (e.g. "data:image/jpeg;base64,")
      const contentOnly = base64Content.includes(',')
        ? base64Content.split(',')[1]
        : base64Content;

      const request = {
        documentType: this.selectedDocumentType,
        mimeType: this.selectedFile.type,
        content: contentOnly
      };

      this.alpacaService.uploadDocument(request)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: () => {
            this.isLoading = false;
            this.accountStatusService.clearActionRequired();
            this.toastService.showToast('Document uploaded successfully!', 'success');
            this.router.navigateByUrl('/my-profile', { replaceUrl: true });
          },
          error: (err) => {
            this.isLoading = false;
            const msg = err.error?.message || err.message || 'Upload failed. Please try again.';
            this.toastService.showToast(msg, 'danger');
          }
        });
    } catch (err) {
      this.isLoading = false;
      this.toastService.showToast('Failed to read file. Please try again.', 'danger');
    }
  }

  private fileToBase64(file: File): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as string);
      reader.onerror = () => reject(new Error('FileReader error'));
      reader.readAsDataURL(file);
    });
  }

  goBack() {
    this.router.navigateByUrl('/my-profile', { replaceUrl: true });
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
