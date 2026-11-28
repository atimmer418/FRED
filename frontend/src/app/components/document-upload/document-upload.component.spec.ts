import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { of, throwError } from 'rxjs';
import { DocumentUploadComponent } from './document-upload.component';
import { AlpacaService } from '../../services/alpaca.service';
import { AccountStatusService } from '../../services/account-status.service';
import { ToastService } from '../../services/toast.service';

describe('DocumentUploadComponent toasts', () => {
  let router: jasmine.SpyObj<Router>;
  let alpacaService: jasmine.SpyObj<AlpacaService>;
  let accountStatusService: jasmine.SpyObj<AccountStatusService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let component: DocumentUploadComponent;

  beforeEach(() => {
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    const navCtrl = jasmine.createSpyObj<NavController>('NavController', ['navigateBack']);
    alpacaService = jasmine.createSpyObj<AlpacaService>('AlpacaService', ['uploadDocument']);
    accountStatusService = jasmine.createSpyObj<AccountStatusService>('AccountStatusService', ['clearActionRequired']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['showToast']);

    component = new DocumentUploadComponent(router, navCtrl, alpacaService, accountStatusService, toastService);
    component.selectedDocumentType = 'identity_verification';
    component.selectedFile = new File(['not really an image'], 'id.png', { type: 'image/png' });
  });

  it('marks a finished upload as success and leaves for the profile', async () => {
    alpacaService.uploadDocument.and.returnValue(of({}));

    await component.uploadDocument();

    expect(alpacaService.uploadDocument).toHaveBeenCalledWith(jasmine.objectContaining({
      documentType: 'identity_verification',
      mimeType: 'image/png',
    }));
    expect(accountStatusService.clearActionRequired).toHaveBeenCalled();
    expect(toastService.showToast).toHaveBeenCalledOnceWith('Document uploaded successfully!', 'success');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/my-profile', { replaceUrl: true });
  });

  it('marks a refused upload as danger, carrying the server message', async () => {
    alpacaService.uploadDocument.and.returnValue(throwError(() => ({ error: { message: 'Document too blurry.' } })));

    await component.uploadDocument();

    expect(toastService.showToast).toHaveBeenCalledOnceWith('Document too blurry.', 'danger');
    expect(accountStatusService.clearActionRequired).not.toHaveBeenCalled();
    expect(component.isLoading).toBeFalse();
  });

  it('never shows the browser\'s "Http failure response for <address>" text when the server sent no sentence', async () => {
    alpacaService.uploadDocument.and.returnValue(throwError(() =>
      new HttpErrorResponse({ status: 500, url: 'https://api.example.test/api/alpaca/upload-document' })));

    await component.uploadDocument();

    expect(toastService.showToast).toHaveBeenCalledOnceWith('Upload failed. Please try again.', 'danger');
  });

  function pick(file: File): void {
    component.selectedFile = null;
    component.onFileSelected({ target: { files: [file] } } as unknown as Event);
  }

  it('refuses a file type the broker does not take, with a sentence the user can act on', () => {
    for (const type of ['image/heic', 'image/gif', 'image/webp', 'text/html', '']) {
      pick(new File(['x'], 'photo', { type }));

      expect(component.selectedFile).toBeNull();
      expect(component.fileError).toBe('Please choose a JPEG, PNG or PDF.');
      expect(component.canUpload).toBeFalse();
    }
  });

  it('refuses a file over 7 MB, the most that fits the broker\'s limit once encoded', () => {
    const big = new File([new Uint8Array(7 * 1024 * 1024 + 1)], 'id.jpg', { type: 'image/jpeg' });
    pick(big);

    expect(component.selectedFile).toBeNull();
    expect(component.fileError).toBe('That file is over 7 MB. Please choose a smaller one.');
  });

  it('accepts a JPEG, a PNG and a PDF', () => {
    for (const type of ['image/jpeg', 'image/png', 'application/pdf']) {
      pick(new File(['x'], 'doc', { type }));

      expect(component.fileError).toBeNull();
      expect(component.selectedFile).not.toBeNull();
    }
  });

  it('marks a file that could not be read as danger, before anything is sent', async () => {
    spyOn(FileReader.prototype, 'readAsDataURL').and.callFake(function (this: FileReader) {
      this.onerror?.(new ProgressEvent('error') as ProgressEvent<FileReader>);
    });

    await component.uploadDocument();

    expect(alpacaService.uploadDocument).not.toHaveBeenCalled();
    expect(toastService.showToast).toHaveBeenCalledOnceWith('Failed to read file. Please try again.', 'danger');
    expect(component.isLoading).toBeFalse();
  });
});
