import { Component, OnInit } from '@angular/core';
import { NavController } from '@ionic/angular';
import { CommonModule } from '@angular/common';
import { IonicModule, ModalController } from '@ionic/angular';
import { PortfolioService } from '../../services/portfolio.service';
import { LoadingController } from '@ionic/angular';
import { ToastService } from '../../services/toast.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { httpStatusOf, isNetworkFailure } from '../../services/analytics/analytics-error.utils';
import { addIcons } from 'ionicons';
import { documentTextOutline, documentAttachOutline, downloadOutline, chevronDownOutline, chevronForwardOutline } from 'ionicons/icons';
import { PdfViewerModalComponent } from '../../components/pdf-viewer-modal/pdf-viewer-modal.component';

interface TaxDocument {
  id: string;
  type: string;
  date: string;
  year: number;
}

interface StatementYear {
  year: number;
  expanded: boolean;
  months: { label: string; doc: TaxDocument }[];
}

@Component({
  selector: 'app-tax-documents',
  templateUrl: './tax-documents.page.html',
  styleUrls: ['./tax-documents.page.scss'],
  standalone: true,
  imports: [CommonModule, IonicModule]
})
export class TaxDocumentsPage implements OnInit {
  loading = true;

  /** Tax forms grouped by year (e.g. 1099s) */
  taxFormsByYear: { year: number; doc: TaxDocument }[] = [];

  /** Monthly account statements grouped by year */
  statementYears: StatementYear[] = [];

  constructor(
    private portfolioService: PortfolioService,
    private loadingController: LoadingController,
    private toastService: ToastService,
    private modalController: ModalController,
    private navCtrl: NavController,
    private analytics: AnalyticsService
  ) {
    addIcons({ documentTextOutline, documentAttachOutline, downloadOutline, chevronDownOutline, chevronForwardOutline });
  }

  goBack() {
    this.navCtrl.navigateBack('/tabs/tab3');
  }

  ngOnInit() {
    this.loadDocuments();
  }

  async loadDocuments() {
    this.loading = true;

    // Fetch documents going back several years so we capture all tax forms & statements
    const now = new Date();
    const end = now.toISOString().split('T')[0];

    const yearsBack = new Date(now);
    yearsBack.setFullYear(now.getFullYear() - 3);
    const start = yearsBack.toISOString().split('T')[0];

    this.portfolioService.getTaxDocuments(start, end).subscribe({
      next: (docs) => {
        const allDocs: TaxDocument[] = (docs || [])
          .filter((doc: any) => !doc.type.includes('trade_confirmation') && !doc.type.includes('account_application'))
          .map((doc: any) => ({
            ...doc,
            year: new Date(doc.date).getFullYear()
          }));

        // --- Tax forms (1099s, etc.) ---
        const taxForms = allDocs.filter(d => d.type.includes('tax_form') || d.type.includes('tax_'));
        // Keep one per year, most recent first
        const taxMap = new Map<number, TaxDocument>();
        taxForms.forEach(d => {
          if (!taxMap.has(d.year) || d.date > taxMap.get(d.year)!.date) {
            taxMap.set(d.year, d);
          }
        });
        this.taxFormsByYear = Array.from(taxMap.entries())
          .sort((a, b) => b[0] - a[0])
          .map(([year, doc]) => ({ year, doc }));

        // --- Account statements (monthly) ---
        const statements = allDocs.filter(d => d.type.includes('account_statement'));
        const stmtMap = new Map<number, TaxDocument[]>();
        statements.forEach(d => {
          if (!stmtMap.has(d.year)) stmtMap.set(d.year, []);
          stmtMap.get(d.year)!.push(d);
        });

        this.statementYears = Array.from(stmtMap.entries())
          .sort((a, b) => b[0] - a[0])
          .map(([year, docs]) => ({
            year,
            expanded: false,
            months: docs
              .sort((a, b) => b.date.localeCompare(a.date))
              .map(d => ({
                label: new Date(d.date + 'T00:00:00').toLocaleString('default', { month: 'long', year: 'numeric' }),
                doc: d
              }))
          }));

        this.loading = false;
      },
      error: (err) => {
        console.error('Error loading documents', err);
        this.loading = false;
      }
    });
  }

  toggleYear(sy: StatementYear) {
    const wasExpanded = sy.expanded;
    // Collapse all years first (accordion behavior)
    this.statementYears.forEach(year => year.expanded = false);
    // Toggle the clicked year
    sy.expanded = !wasExpanded;
  }

  /** Open a document in the in-app PDF viewer with export capabilities */
  async openDocument(doc: TaxDocument) {
    const loading = await this.loadingController.create({ message: 'Loading document...' });
    await loading.present();

    this.portfolioService.downloadDocument(doc.id).subscribe({
      next: async (blob) => {
        loading.dismiss();
        
        console.log('Received blob - size:', blob.size, 'type:', blob.type);
        
        if (blob.size === 0) {
          console.error('Received empty blob');
          this.analytics.trackErrorShown('document', 'portfolio', 'toast', null, false);
          await this.toastService.showToast('Document is empty', 'danger', 2000);
          return;
        }
        
        // Read first few bytes to verify it's a PDF
        const reader = new FileReader();
        reader.onload = async (e) => {
          const arrayBuffer = e.target?.result as ArrayBuffer;
          const uint8Array = new Uint8Array(arrayBuffer);
          const header = String.fromCharCode(...uint8Array.slice(0, 5));
          console.log('PDF header check:', header);
          
          if (!header.startsWith('%PDF-')) {
            console.error('Not a valid PDF! First 100 bytes:', String.fromCharCode(...uint8Array.slice(0, Math.min(100, uint8Array.length))));
            this.analytics.trackErrorShown('document', 'portfolio', 'toast', null, false);
            await this.toastService.showToast('Invalid document format', 'danger', 2000);
            return;
          }
          
          // Create blob with explicit PDF MIME type
          const pdfBlob = new Blob([blob], { type: 'application/pdf' });
          
          // Determine document title based on type
          let title = 'Document';
          if (doc.type.includes('tax_form')) {
            title = `${doc.year} Alpaca Form 1099`;
          } else if (doc.type.includes('account_statement')) {
            const date = new Date(doc.date + 'T00:00:00');
            const monthYear = date.toLocaleString('default', { month: 'short', year: 'numeric' });
            title = `${monthYear} Alpaca Statement`;
          }
          
          // Open PDF viewer modal
          const modal = await this.modalController.create({
            component: PdfViewerModalComponent,
            componentProps: {
              pdfBlob: pdfBlob,
              documentTitle: title
            }
          });
          
          await modal.present();
        };
        reader.readAsArrayBuffer(blob);
      },
      error: async (err) => {
        loading.dismiss();
        console.error('Error downloading document:', err);
        this.analytics.trackErrorShown(
          isNetworkFailure(err) ? 'network' : 'document',
          'portfolio',
          'toast',
          httpStatusOf(err),
          false,
        );
        await this.toastService.showToast('Failed to load document', 'danger', 2000);
      }
    });
  }
}
