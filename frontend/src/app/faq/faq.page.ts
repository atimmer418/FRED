import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IonicModule } from '@ionic/angular';
import { Router } from '@angular/router';
import { NavController } from '@ionic/angular';
import { addIcons } from 'ionicons';
import {
    chevronDownOutline,
    chevronUpOutline,
    helpCircleOutline,
    arrowBackOutline,
    walletOutline,
    timeOutline,
    calendarOutline,
    documentTextOutline,
    shieldCheckmarkOutline,
    informationCircleOutline,
    businessOutline,
    cashOutline,
    statsChartOutline
} from 'ionicons/icons';

interface FaqItem {
    question: string;
    answer: string;
    isOpen: boolean;
    icon: string;
}

@Component({
    selector: 'app-faq',
    templateUrl: './faq.page.html',
    styleUrls: ['./faq.page.scss'],
    standalone: true,
    imports: [CommonModule, FormsModule, IonicModule]
})
export class FaqPage {

    faqs: FaqItem[] = [
        {
            question: 'What is FRED doing exactly?',
            answer: 'FRED is your automated investment pilot designed to buy you time back in your life by helping you retire earlier than you would have otherwise. We handle the complex logic of recurring trades and navigating optimal retirement strategy planning. Additionally, FRED acts as a resource available to answer your questions about early retirement planning and its dependency on long term investing. We partner with Plaid and Alpaca Securities to securely hold your assets and execute the actual trades, ensuring your money is handled by a regulated custodian.',
            isOpen: false,
            icon: 'information-circle-outline'
        },
        {
            question: 'Is my money safe?',
            answer: 'Your brokerage account is held with Alpaca Securities LLC, a member of SIPC, which protects securities customers of its members up to $500,000 (including $250,000 for claims for cash). This is the same protection limit provided by other major brokerages like Fidelity and Schwab. FRED uses bank-level encryption to secure your data.\n\n<strong>Short answer: Yes, your money is safe.</strong>',
            isOpen: false,
            icon: 'shield-checkmark-outline'
        },
        {
            question: 'When will my investments go through?',
            answer: 'The ACH transfer is initiated on your Transfer Date. Once it completes (typically 1-3 business days later), the recurring purchase orders for your current portfolio are placed during market hours.',
            isOpen: false,
            icon: 'time-outline'
        },
        {
            question: 'Does the investment schedule keep my selected day?',
            answer: 'Yes! We always aim to execute on your exact preferred day. If that day falls on a weekend or market holiday, we simply shift that specific investment to the next available business day. Your future investments will stay on your original schedule.',
            isOpen: false,
            icon: 'calendar-outline'
        },
        {
            question: 'What type of account do I get with Alpaca through FRED?',
            answer: 'You have a personal brokerage account with Alpaca Securities, registered entirely in your name. This gives you full ownership and control over the account. At any point, you can liquidate your holdings or transfer your assets to another brokerage service.',
            isOpen: false,
            icon: 'business-outline'
        },
        {
            question: 'How do taxes work and how do I report them?',
            answer: 'As with any brokerage account, you are responsible for reporting capital gains and dividends. We (via our partner Alpaca) will generate a consolidated Form 1099 by mid-February each year. You can download this document directly from the app to use for your tax filing.',
            isOpen: false,
            icon: 'document-text-outline'
        },
        {
            question: 'What do the different portfolio values mean?',
            answer: '<strong>Total Equity</strong> is your complete account value (Portfolio Value + Buying Power).\n<strong>Portfolio Value</strong> is your stock holdings\' market value (Total Invested + Total G/L).\n<strong>Total Invested</strong> is the total amount of money you have contributed to your portfolio.\n<strong>Buying Power</strong> is funds available for immediate trading.\n<strong>Settled Cash</strong> is withdrawn-ready funds (typically available 1 day after selling stocks).',
            isOpen: false,
            icon: 'cash-outline'
        },
        {
            question: 'What do the Portfolio Insight table columns mean?',
            answer: '<strong>Symbol</strong> shows a stock ticker and company name.\n<strong>Quantity</strong> is how many shares you own.\n<strong>Avg Cost</strong> is the average price you paid per share.\n<strong>Current</strong> is today\'s market price per share.\n<strong>Value</strong> is your position\'s total market value (Quantity × Current).\n<strong>Day G/L</strong> shows today\'s profit or loss.\n<strong>Total G/L</strong> shows your total profit or loss since buying.\n<strong>% Account</strong> shows what percentage of your total portfolio this position represents.',
            isOpen: false,
            icon: 'stats-chart-outline'
        },
        {
            question: 'How long for buying power to become settled cash?',
            answer: 'Typically, trade settlement takes 1 business day after the sell date (T+1). Once settled, the funds become "Available to Withdraw".',
            isOpen: false,
            icon: 'wallet-outline'
        }
    ];

    constructor(private router: Router, private navCtrl: NavController) {
        addIcons({
            chevronDownOutline,
            chevronUpOutline,
            helpCircleOutline,
            arrowBackOutline,
            walletOutline,
            timeOutline,
            calendarOutline,
            documentTextOutline,
            shieldCheckmarkOutline,
            informationCircleOutline,
            businessOutline,
            cashOutline,
            statsChartOutline
        });
    }

    toggleSection(index: number) {
        // Toggle the clicked section
        const wasOpen = this.faqs[index].isOpen;

        // Close all sections first (accordion behavior)
        this.faqs.forEach(faq => faq.isOpen = false);

        // If it wasn't open before, open it now
        if (!wasOpen) {
            this.faqs[index].isOpen = true;
        }
    }

    goBack() {
        this.navCtrl.navigateBack('/tabs/tab3');
    }
}
