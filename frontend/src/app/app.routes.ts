import { Routes } from '@angular/router';
import { LinkPlaidComponent } from './components/linkplaid/linkplaid.component';

export const routes: Routes = [
  {
    path: 'survey-initial',
    loadComponent: () => import('./components/surveyinitial/surveyinitial.component').then(m => m.SurveyInitialComponent),
    data: { onboardingOnly: true }
  },
  {
    path: 'fi-plan-results',
    loadComponent: () => import('./components/fi-plan-results/fi-plan-results.component').then(m => m.FiPlanResultsComponent),
    data: { onboardingOnly: true }
  },
  {
    path: 'auth-finalize', // This is the route you navigate to
    loadComponent: () => import('./components/authfinalize/authfinalize.component').then(m => m.AuthFinalizeComponent)
    // Adjust path if you placed AuthFinalizeComponent elsewhere, e.g., directly under 'app/'
    // Not onboardingOnly: AppLockService.forceRecovery() sends logged-in users here.
  },
  {
    path: 'kyc-verification',
    loadComponent: () => import('./components/kyc-verification/kyc-verification.component').then(m => m.KycVerificationComponent)
    // Not onboardingOnly: Security Settings "Update KYC" (?edit=true) is a post-onboarding flow.
  },
  {
    path: 'recovery',
    loadComponent: () => import('./pages/recovery/recovery.page').then(m => m.RecoveryPage),
    data: { onboardingOnly: true }
  },
  {
    path: 'investment-confirmation',
    loadComponent: () => import('./components/investmentconfirmation/investmentconfirmation.component').then(m => m.InvestmentConfirmationComponent),
    // Or from './pages/investment-confirmation/investment-confirmation.page' if you use .page convention
    data: { onboardingOnly: true }
  },
  {
    path: 'link-bank',
    component: LinkPlaidComponent
  },
  {
    path: 'investment-schedule',
    loadComponent: () => import('./components/investment-schedule/investment-schedule.component').then(m => m.InvestmentScheduleComponent),
    data: { onboardingOnly: true }
  },
  {
    path: 'portfolio-customize',
    loadComponent: () => import('./components/portfolio-customize/portfolio-customize.component').then(m => m.PortfolioCustomizeComponent)
  },
  {
    path: 'portfolio-dashboard',
    loadComponent: () => import('./components/portfolio-dashboard/portfolio-dashboard.component').then(m => m.PortfolioDashboardComponent)
  },
  {
    path: 'tax-documents',
    loadComponent: () => import('./pages/tax-documents/tax-documents.page').then(m => m.TaxDocumentsPage)
  },
  {
    path: 'security-settings',
    loadComponent: () => import('./pages/security-settings/security-settings.page').then(m => m.SecuritySettingsPage)
  },
  {
    path: 'change-email',
    loadComponent: () => import('./pages/change-email/change-email.page').then(m => m.ChangeEmailPage)
  },
  {
    path: 'get-started',
    loadComponent: () => import('./components/get-started/get-started.component').then(m => m.GetStartedComponent),
    // TODO: This route might need to be set as the initial app route for new users,
    // or integrated into a guard that redirects new users here.
    data: { onboardingOnly: true }
  },
  {
    path: 'recurring-investments',
    loadComponent: () => import('./recurring-investments/recurring-investments.page').then(m => m.RecurringInvestmentsPage)
  },
  {
    path: 'lump-sum-investment',
    loadComponent: () => import('./lump-sum-investment/lump-sum-investment.page').then(m => m.LumpSumInvestmentPage)
  },
  {
    path: 'change-bank-account',
    loadComponent: () => import('./change-bank-account/change-bank-account.page').then(m => m.ChangeBankAccountPage)
  },
  {
    path: 'beneficiaries',
    loadComponent: () => import('./beneficiaries/beneficiaries.page').then(m => m.BeneficiariesPage)
  },
  {
    path: 'beneficiaries/add',
    loadComponent: () => import('./beneficiaries/add-beneficiary.page').then(m => m.AddBeneficiaryPage)
  },
  {
    path: 'beneficiaries/edit/:id',
    loadComponent: () => import('./beneficiaries/add-beneficiary.page').then(m => m.AddBeneficiaryPage)
  },
  {
    path: 'sell-withdraw',
    loadComponent: () => import('./sell-withdraw/sell-withdraw.page').then(m => m.SellWithdrawPage)
  },
  {
    path: 'faq',
    loadComponent: () => import('./faq/faq.page').then(m => m.FaqPage)
  },
  {
    path: 'tabs',
    loadChildren: () => import('./tabs/tabs.routes').then((m) => m.routes),
  },
  {
    path: '',
    redirectTo: 'get-started',
    pathMatch: 'full'
  },
  // Goals: the quiz builds or edits a plan; the goal page shows it. The two `plan` routes preview
  // the quiz's draft (data.preview) with a Start / Update CTA; `goals/:id` is the saved plan.
  {
    path: 'goals/new/:type/plan',
    data: { preview: true },
    loadComponent: () => import('./pages/goal/goal.page').then(m => m.GoalPage)
  },
  {
    path: 'goals/new/:type',
    loadComponent: () => import('./pages/goal-quiz/goal-quiz.page').then(m => m.GoalQuizPage)
  },
  {
    path: 'goals/:id/edit/plan',
    data: { preview: true },
    loadComponent: () => import('./pages/goal/goal.page').then(m => m.GoalPage)
  },
  {
    path: 'goals/:id/edit',
    loadComponent: () => import('./pages/goal-quiz/goal-quiz.page').then(m => m.GoalQuizPage)
  },
  {
    path: 'goals/:id',
    loadComponent: () => import('./pages/goal/goal.page').then(m => m.GoalPage)
  },
  {
    path: 'my-profile',
    loadComponent: () => import('./pages/my-profile/my-profile.page').then( m => m.MyProfilePage)
  },
  {
    path: 'document-upload',
    loadComponent: () => import('./components/document-upload/document-upload.component').then(m => m.DocumentUploadComponent)
  },
];
