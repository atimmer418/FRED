import { Component, OnInit, OnDestroy, effect } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';
import {
  IonHeader,
  IonContent,
  IonAvatar,
  IonBadge
} from '@ionic/angular/standalone';
import { SettingsService, UserPreferences, RecurringInvestment } from '../services/settings.service';
import { PlaidService } from '../services/plaid.service';
import { ToastService } from '../services/toast.service';
import { MfuStoreService } from '../services/mfu-store.service';
import { MfuPresenterService } from '../services/mfu-presenter.service';
import { AccountStatusService } from '../services/account-status.service';
import { FreedomStatsService } from '../services/freedom-stats.service';
import { TabActivationService } from '../services/tab-activation.service';
import { Observable } from 'rxjs';
import { EquityPigUtils } from '../utils/equity-pig.utils';
import { TabBarScrollDirective } from '../directives/tab-bar-scroll.directive';
import { SkeletonComponent } from '../components/skeleton/skeleton.component';

interface SettingSection {
  title: string;
  items: SettingItem[];
}

interface SettingItem {
  title: string;
  subtitle?: string;
  icon: string;
  badge?: string;
  badgeColor?: string;
  action: string;
  type?: 'navigation' | 'toggle' | 'action';
}

@Component({
  selector: 'app-tab3',
  templateUrl: 'tab3.page.html',
  styleUrls: ['tab3.page.scss'],
  imports: [
    CommonModule,
    IonHeader,
    IonContent,
    IonAvatar,
    IonBadge,
    TabBarScrollDirective,
    SkeletonComponent
  ],
})
export class Tab3Page implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  public userPreferences: UserPreferences | null = null;
  public recurringInvestment: RecurringInvestment | null = null;
  public userEmail: string = '';
  public hasMfuPeriod: boolean = false;
  public profileActionRequired$: Observable<boolean>;

  // --- Profile avatar (live equity-based pig, fed from the stats service) ---
  public liveEquity: number = 0;

  get pigAvatarSrc(): string {
    return EquityPigUtils.pigSrcFromEquity(this.liveEquity);
  }

  public settingSections: SettingSection[] = [
    {
      title: 'Investment Management',
      items: [
        {
          title: 'Recurring Investments',
          subtitle: 'Adjust or pause your automatic investments',
          icon: 'schedule',
          action: 'recurringInvestments',
          type: 'navigation'
        },
        {
          title: 'One-Time Transactions',
          subtitle: 'Invest once or transfer assets into Alpaca',
          icon: 'payments',
          action: 'lumpSumInvestment',
          type: 'navigation'
        },
        {
          title: 'Portfolio Allocation',
          subtitle: 'Customize your investment strategy',
          icon: 'pie_chart',
          action: 'portfolioAllocation',
          type: 'navigation'
        },
        {
          title: 'Sell & Withdraw',
          subtitle: 'Sell stocks and withdraw money',
          icon: 'credit_card',
          action: 'sellWithdraw',
          type: 'navigation'
        }
      ]
    },
    {
      title: 'Account & Security',
      items: [
        {
          title: 'Account Security',
          subtitle: 'App locking, identity and email',
          icon: 'verified_user',
          action: 'security',
          type: 'navigation'
        },
        {
          title: 'Change Bank Account',
          subtitle: 'Link a different bank account with Plaid',
          icon: 'account_balance',
          action: 'changeBankAccount',
          type: 'navigation'
        },
        {
          title: 'Documents',
          subtitle: 'Tax forms & account statements',
          icon: 'description',
          action: 'taxDocuments',
          type: 'navigation'
        },
        {
          title: 'Beneficiaries',
          subtitle: 'Manage account beneficiaries and TOD',
          icon: 'group',
          action: 'beneficiaries',
          type: 'navigation'
        }
      ]
    },
    {
      title: 'Support & Legal',
      items: [
        {
          title: 'FAQs',
          subtitle: 'Common questions answered',
          icon: 'help',
          action: 'faq',
          type: 'navigation'
        },
        {
          title: 'Contact Us',
          subtitle: 'Get help from our team',
          icon: 'mail',
          action: 'contactSupport',
          type: 'navigation'
        },
        {
          title: 'Legal Information',
          subtitle: 'Terms of Service & Privacy Policy',
          icon: 'menu_book',
          action: 'legalInformation',
          type: 'navigation'
        }
      ]
    }
  ];

  constructor(
    private router: Router,
    private settingsService: SettingsService,
    private plaidService: PlaidService,
    private toastService: ToastService,
    private mfuStore: MfuStoreService,
    private mfuPresenter: MfuPresenterService,
    private accountStatusService: AccountStatusService,
    public freedomStatsService: FreedomStatsService,
    private tabActivation: TabActivationService
  ) {
    this.profileActionRequired$ = this.accountStatusService.actionRequired$;

    // Keep liveEquity in sync with the service equity signal so the FRED-195
    // pig avatar always reflects the latest portfolio value.
    effect(() => {
      this.liveEquity = this.freedomStatsService.equity() ?? 0;
    });

    // Fire the tab's "on enter" work when it becomes the active pager slide.
    effect(() => {
      if (this.tabActivation.activeTab() === 'tab3') {
        this.onTabActivated();
      }
    });
  }

  ngOnInit() {
    const storedEmail = localStorage.getItem('userEmail');
    if (storedEmail) {
      this.userEmail = storedEmail;
    }

    this.initializeSettingSections();
    this.setupDemoData();

    // Subscribe to user preferences
    this.settingsService.getPreferences()
      .pipe(takeUntil(this.destroy$))
      .subscribe(preferences => {
        this.userPreferences = preferences;
      });

    // Subscribe to recurring investment
    this.settingsService.getRecurringInvestment()
      .pipe(takeUntil(this.destroy$))
      .subscribe(investment => {
        this.recurringInvestment = investment;
      });
  }

  /**
   * Runs each time Profile becomes the active pager slide (fired by the
   * activation effect).
   */
  onTabActivated() {
    // Re-check MFU availability each time the tab is visited.
    this.checkMfuAvailability();

    // Silent stale-while-revalidate refresh: show cached stats instantly,
    // then quietly background-refresh. Never flashes '—' on re-entry.
    this.freedomStatsService.refresh();
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private initializeSettingSections() {
    // The settingSections property is already defined as a static array above.
  }

  private checkMfuAvailability() {
    this.mfuStore.ensureAutoSession()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (session) => {
          // hasMfuHistory indicates the user has had at least one MFU generated.
          this.hasMfuPeriod = !!(session.hasMfuHistory || session.shouldShow);

          // Auto-show (present-when-ready). The shared claim dedupes with the dashboard so only one
          // of the two presents the popup.
          if (session.shouldShow) {
            this.mfuPresenter.presentAutoIfDue();
          }
        },
        error: () => {
          this.hasMfuPeriod = false;
        }
      });
  }

  onFredLogoClick() {
    if (this.hasMfuPeriod) {
      this.openMonthlyFreedomUpdate();
    }
  }

  private setupDemoData() {
    // Set up some demo recurring investment data if none exists
    if (!localStorage.getItem('recurringInvestment')) {
      const demoInvestment: RecurringInvestment = {
        id: 'demo-1',
        amount: 250,
        frequency: 'monthly',
        nextDate: this.settingsService.getNextInvestmentDate('monthly'),
        isActive: true,
        portfolioAllocation: {
          'VTI': 75,   // Total Stock Market
          'VXUS': 20,  // International
          'VBR': 5     // Small-Cap Value
        }
      };
      this.settingsService.updateRecurringInvestment(demoInvestment);
    }
  }

  onSettingClick(action: string) {
    console.log('Clicked setting:', action);

    switch (action) {
      case 'recurringInvestments':
        this.handleRecurringInvestments();
        break;
      case 'lumpSumInvestment':
        this.handleLumpSumInvestment();
        break;
      case 'portfolioAllocation':
        this.router.navigate(['/portfolio-customize']);
        break;
      case 'sellWithdraw':
        this.router.navigate(['/sell-withdraw']);
        break;
      case 'investmentGoals':
        this.showComingSoon('Investment Goals');
        break;
      case 'personalInfo':
      case 'changeEmail':
        // These are now handled in security settings
        this.router.navigate(['/security-settings']);
        break;
      case 'security':
        this.router.navigate(['/security-settings']);
        break;
      case 'bankAccounts':
        this.handleBankAccountsView();
        break;
      case 'changeBankAccount':
        this.handleChangeBankAccount();
        break;
      case 'taxDocuments':
        this.router.navigate(['/tax-documents']);
        break;
      case 'beneficiaries':
        this.handleBeneficiaries();
        break;
      case 'notifications':
        this.handleNotifications();
        break;
      case 'theme':
        this.toggleTheme();
        break;
      case 'faq':
        this.router.navigate(['/faq']);
        break;
      case 'helpCenter':
        this.showComingSoon('Help Center');
        break;
      case 'contactSupport':
        this.handleContactSupport();
        break;
      case 'legalInformation':
        this.showComingSoon('Legal Information');
        break;
      case 'monthlyFreedomUpdate':
        this.openMonthlyFreedomUpdate();
        break;
      default:
        console.log('Unknown action:', action);
    }
  }

  private handleRecurringInvestments() {
    // Navigate to the recurring investments management page
    this.router.navigate(['/recurring-investments']);
  }

  private handleLumpSumInvestment() {
    this.router.navigate(['/lump-sum-investment']);
  }

  goToMyProfile() {
    this.router.navigate(['/my-profile']);
  }

  private handleNotifications() {
    if (this.userPreferences) {
      const notifCount = Object.values(this.userPreferences.notifications).filter(Boolean).length;
      this.toastService.showToast(`You have ${notifCount} notification types enabled`, 'primary');
    }
  }

  private handleContactSupport() {
    window.location.href = 'mailto:help@fredvested.com';
  }

  private handleBankAccountsView() {
    // Fetch bank account on-demand instead of eagerly on tab load
    this.plaidService.refreshBankAccountData();
    this.plaidService.getCurrentAccount()
      .pipe(takeUntil(this.destroy$))
      .subscribe(account => {
        if (account) {
          const linkDate = this.plaidService.formatLinkDate(account.linkDate);
          const status = this.plaidService.getStatusText(account.status);
          this.toastService.showToast(`${account.institutionName} (${status}) - Linked: ${linkDate}`, 'primary');
        } else {
          this.toastService.showToast('No bank accounts linked. Use "Change Bank Account" to link one.', 'warning');
        }
      });
  }

  private handleChangeBankAccount() {
    // Navigate to the dedicated change bank account page
    this.router.navigate(['/change-bank-account']);
  }

  private showComingSoon(feature: string) {
    this.toastService.showToast(`${feature} feature coming soon!`, 'warning');
  }

  private handleBeneficiaries() {
    console.log('Navigating to beneficiaries management');
    this.router.navigate(['/beneficiaries']);
  }

  private toggleTheme() {
    if (this.userPreferences) {
      const newTheme = this.userPreferences.theme === 'dark' ? 'light' : 'dark';
      this.settingsService.applyTheme(newTheme);
      this.toastService.showToast(`Switched to ${newTheme} theme`, 'success');
    }
  }

  async openMonthlyFreedomUpdate() {
    // Reopen: the presenter paints instantly from the localStorage snapshot (0 round-trips) and the
    // component revalidates the live/projection fields in the background.
    await this.mfuPresenter.presentReopen();
  }

  onLogout() {
    // Implement logout functionality
    console.log('Logout clicked');
    // this.router.navigate(['/login']);
  }
}
