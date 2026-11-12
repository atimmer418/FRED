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
