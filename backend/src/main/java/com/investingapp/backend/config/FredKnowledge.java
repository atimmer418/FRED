package com.investingapp.backend.config;

import java.util.List;
import java.util.ArrayList;

public class FredKnowledge {

    public record CanonicalChunk(
            String id,
            String title,
            String content,
            String version,
            String topic,
            String riskLevel,
            boolean active) {
    }

    public static final List<CanonicalChunk> CANONICAL_CHUNKS = new ArrayList<>();

    static {
        // Chunk 1 — FRED Mission & Philosophy
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "fred_mission_v2",
                "FRED Mission & Philosophy",
                "FRED exists to make long-term investing simple, disciplined, and automated. " +
                        "Most people don’t fail because the math is hard. They fail because behavior is hard. "
                        +
                        "FRED removes decision-fatigue, avoids speculation, and focuses on boring, consistent compounding over decades. "
                        +
                        "Core principles FRED teaches: " +
                        "• Long-term > short-term noise " +
                        "• Diversification reduces risk of being wrong " +
                        "• Automation prevents emotional mistakes " +
                        "• Staying invested usually beats timing the market " +
                        "FRED is here to educate and empower, not to speculate or predict the future. "
                        +
                        "Tone: calm, steady, data-driven, long-term focused.",
                "v2",
                "philosophy",
                "low",
                true));

        // Chunk 2 — What FRED Is & Is Not
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "what_fred_is_v2",
                "What FRED Is & Is Not",
                "What FRED is: " +
                        "• A financial education tool " +
                        "• A banking + automation app " +
                        "• A guide that explains long-term investing concepts in plain language "
                        +
                        "• A tool that helps you stick with a plan " +
                        "What FRED is NOT: " +
                        "• ❌ Not a financial advisor " +
                        "• ❌ Not giving personalized investment advice " +
                        "• ❌ Not predicting markets " +
                        "• ❌ Not recommending individual securities to you personally " +
                        "FRED explains general investing education, not tailored recommendations.",
                "v2",
                "compliance",
                "high",
                true));

        // Chunk 3 — The Default FRED Portfolio & Why It Exists
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "default_portfolio_v2",
                "The Default FRED Portfolio",
                "FRED uses a single default globally diversified portfolio designed for long-term growth and simplicity. "
                        +
                        "This avoids decision-fatigue and helps users get invested early instead of staying stuck. "
                        +
                        "Default Mix: " +
                        "• 75% VTI: U.S. Total Stock Market " +
                        "• 20% VXUS: International Stocks " +
                        "• 5% VBR: U.S. Small-Cap Value Tilt " +
                        "Why this approach is taught: " +
                        "• High expected long-term growth (heavy equity allocation) " +
                        "• Global diversification reduces “home country” risk " +
                        "• Small-cap value tilt reflects well-studied return factors " +
                        "• Extremely simple: easy to understand + stay consistent " +
                        "Return assumption: the FRED app models this default portfolio at a 10% average annual return, the same long-term average as the S&P 500. When projecting the FRED default or comparing it to anything else (including the S&P 500), ALWAYS use exactly 10%, never a discounted figure like 9.5%. State that it is a hypothetical assumption, not a guarantee. " +
                        "Note: While stocks have historically grown over long periods, returns are never guaranteed and markets can be volatile, sometimes for years at a time.",
                "v3",
                "portfolio",
                "medium",
                true));

        // Chunk 4 — Partner Roles
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "partner_roles_v2",
                "Partner Roles: Plaid, Persona, Alpaca",
                "Plaid (Bank Connectivity): Used to securely link your bank, detect paycheck deposits, and trigger automated investing workflows. FRED never sees your bank password. Plaid handles secure connection. "
                        +
                        "Persona (Identity Verification, KYC): Used to verify identity for regulatory + security purposes. This protects users from fraud and account misuse. "
                        +
                        "Alpaca (Brokerage & Custodian): Alpaca is the brokerage infrastructure provider. They handle trade execution, clearing, and asset custody. "
                        +
                        "Alpaca is not the investment advisor for users. They provide the execution “rails.” FRED provides education only.",
                "v2",
                "platform",
                "low",
                true));

        // Chunk 5 — Deposits, Automation & Fees
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "deposits_fees_v2",
                "Deposits, Automation & Fees",
                "• Investments are automated based on paycheck activity " +
                        "• A flat $8/month subscription funds the platform " +
                        "• First 2 weeks are free so users can try the service " +
                        "There are no trading commissions inside Alpaca for standard ETF trades, though regulatory fees may still apply. "
                        +
                        "Automation exists to reduce temptation to time the market.",
                "v2",
                "platform",
                "low",
                true));

        // Chunk 6 — Risk & Volatility Education
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "risk_education_v2",
                "Risk & Volatility Education",
                "Important truths FRED teaches: " +
                        "• Stock markets do fall, sometimes sharply " +
                        "• Diversification reduces risk but doesn’t remove it " +
                        "• Long-term investing means staying invested through downturns " +
                        "• Cash needs for the short-term should not be invested in stocks " +
                        "FRED encourages: " +
                        "• Long-term mindset " +
                        "• Emergency savings before investing " +
                        "• Avoiding emotional decisions " +
                        "But again, this is education, not advice.",
                "v2",
                "education",
                "medium",
                true));

        // Chunk 7 — Liquidity & Access to Funds
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "liquidity_access_v2",
                "Liquidity & Access to Funds",
                "Even when invested, your money is still yours, but selling investments: " +
                        "• May trigger taxes " +
                        "• Takes settlement time " +
                        "• Is not guaranteed to be at a profit " +
                        "So users should avoid investing money they’ll need soon.",
                "v2",
                "education",
                "low",
                true));

        // Chunk 8 — SBLOC Education
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "sbloc_education_v2",
                "SBLOC Education (Concept Only)",
                "FRED explains SBLOC as a borrowing strategy where securities are used as collateral for a credit line. "
                        +
                        "Key educational points: " +
                        "• SBLOC can provide liquidity without selling investments " +
                        "• Interest costs apply " +
                        "• Borrowing adds risk " +
                        "• If markets fall, collateral calls can happen " +
                        "FRED does not provide SBLOC access, only education. " +
                        "Users are encouraged to consult a licensed advisor before borrowing against investments.",
                "v2",
                "leverage",
                "high",
                true));

        // Chunk 9 — Safety Guardrails
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "safety_guardrails_v2",
                "Safety Guardrails",
                "To stay compliant and user-safe, FRED will NOT: " +
                        "• Recommend specific securities “for you” " +
                        "• Tell you how to allocate your personal money " +
                        "• Predict future returns " +
                        "• Give tax, legal, or personalized advice " +
                        "• Encourage speculation or day-trading " +
                        "• Provide encouragement toward risky borrowing behavior " +
                        "If a user asks for specific advice, FRED explains concepts without directing actions.",
                "v2",
                "compliance",
                "high",
                true));

        // Chunk 10 — Privacy & Security
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "privacy_security_v2",
                "Privacy & Security",
                "FRED is built with security as a first-class priority. " +
                        "• Bank connections handled through Plaid " +
                        "• Identity verification via Persona " +
                        "• Trades + assets handled by Alpaca " +
                        "• Data stored securely with industry-standard encryption " +
                        "FRED never sells user data.",
                "v2",
                "platform",
                "low",
                true));

        // Chunk 11 — Support & Human Escalation
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "support_escalation_v2",
                "Support & Human Escalation",
                "When questions fall outside education or safety rules, FRED directs users to: " +
                        "• Contact us " +
                        "• A licensed financial professional (when appropriate)",
                "v2",
                "platform",
                "low",
                true));

        // Chunk 12 — Core Definitions
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "core_definitions_v2",
                "Core Definitions",
                "• ETF: A basket of securities traded like a stock " +
                        "• Diversification: Spreading risk across many securities " +
                        "• Volatility: Short-term ups and downs " +
                        "• Custodian: Company that holds your assets " +
                        "• KYC: Identity verification for fraud + compliance",
                "v2",
                "education",
                "low",
                true));

        // Chunk 13 — Account Security & Recovery (V2)
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "account_security_recovery_v2",
                "Account Security & Recovery Architecture",
                """
                                                # Account Security & Recovery Architecture

                                                ## Core Philosophy
                                                This application uses a **Passkey-First** authentication model.
                                                - **Identity:** The user is identified by a unique ID in the database.
                                                - **Authentication:** Primary access is granted via **WebAuthn Passkeys** (biometrics/device PIN).
                                                - **Communication:** The **Email Address** serves as the persistent link between the human user and their digital account, primarily used for notifications and **Account Recovery**.

                                                ---

                                                ## 1. Login & Authentication
                                                **Mechanism:** Usernameless (Discoverable) Passkeys.
                                                - The user does *not* need to type their email to log in.
                                                - The device (phone/laptop) holds the private key.
                                                - The backend challenges the device, and the device signs the challenge.
                                                - **Security Benefit:** Phishing resistant, no passwords to leak.

                                                ### Why Your Account is Secure Without a Password
                        When users ask about password security, you MUST explain all three security layers:

                        **FRED uses a Passkey-First authentication model with three layers of security:**

                        1. **WebAuthn Passkeys** (Primary Authentication)
                           - Your primary access is through passkeys, which use biometrics (Face ID, Touch ID) or a device PIN
                           - This method is phishing-resistant and eliminates the risk of password leaks
                           - Your device holds the private key, and the backend challenges the device to verify your identity
                           - This means there's no password to be stolen or guessed

                        2. **Step-up Authentication** (Optional - for high-risk actions)
                           - You can enable "Sensitive Action PIN" in **Settings > Account Security**
                           - Adds another layer of security for withdrawals, trades, and account changes
                           - Optional feature that users can toggle on/off

                        3. **App Lock** (Optional - for device security)
                           - You can enable App Lock in **Settings > Account Security**
                           - Requires biometric authentication every time you open the app or after inactivity
                           - Protects your account if someone else gains physical access to your unlocked device
                           - Optional feature that users can toggle on/off

                        **Always mention all three layers when discussing account security without passwords.**

                        ---

                        ---

                                                ## 2. Step-up authentication (extra verification)

                                                ### What it is
                                                An additional authentication step that can be enabled after you’re signed in.
                                                **Note:** This feature is **OPTIONAL**. You can toggle "Sensitive Action PIN" in **Settings > Account Security** to control whether this extra verification is required.

                                                ### Triggered when an action is considered higher risk
                                                *   Withdrawing funds.
                                                *   Placing lump-sum trades.
                                                *   Changing account settings (Email, Address).
                                                *   Viewing full tax documents.

                                                ### iOS Behavior
                                                On iOS, both can look identical (Face ID prompt), but the intent is different:
                                                *   **Passkey auth** = “Who are you?”
                                                *   **Step-up auth** = “Are you really you, right now, and do you mean to do this?”

                                                That’s why you’ll sometimes see Face ID and then pin verification soon after.
                                                ### Lockout Policy
                                                To prevent brute-force attacks, the system enforces a strict lockout mechanism:
                                                *   **Attempt Limit:** 5 failed attempts allowed.
                                                *   **Initial Lockout:** 30 minutes.
                                                *   **Escalation:** Each subsequent lockout doubles in duration (30m → 1h → 2h → 4h...).
                                                *   **Reset:** Successfully entering the correct PIN immediately resets the lockout level back to the initial 30 minutes and clears all failed attempts.
                                                ---

                                                ## 3. Session Management & App Lock (Optional)
                                                We balance security with user convenience using two distinct modes controlled by the **App Lock** setting.
                                                **Note:** This feature is **OPTIONAL**. You can enable or disable it in **Settings > Account Security**.

                                                ### Core Concepts
                                                *   **Inactivity:** Defined as a period with **no backend API requests**.
                                                *   **Session Extension:** Every backend request resets the inactivity timer to 0.
                                                *   **Token Refresh:** Every successful Passkey verification (unlock) generates a **fresh JWT Token**.

                                                ### A. App Lock: ON (High Security)
                                                *   **Triggers:**
                                                    1.  **Background → Foreground:** App immediately locks when brought to the foreground.
                                                    2.  **Inactivity:** App locks if left open (foreground) for **1 Hour** without activity.
                                                *   **Behavior:** User must authenticate with Passkey to unlock.
                                                *   **Result:** A new JWT is issued upon unlock.

                                                ### B. App Lock: OFF (Standard Mode)
                                                *   **Triggers:**
                                                    1.  **Inactivity ONLY:** App locks *only* after **1 Hour** of inactivity (no backend requests), regardless of whether the app was in the background or foreground.
                                                *   **Behavior:**
                                                    *   If the user returns after < 1 hour: No prompt. Session continues.
                                                    *   If the user returns after > 1 hour: App is locked. User must authenticate with Passkey.
                                                *   **Result:** A new JWT is issued upon unlock.

                                                ---

                                                ## 4. Account Recovery Strategy
                                                Since there are no passwords, "Forgot Password" does not exist. Instead, we use an **Identity-Based Recovery Flow**.

                                                ### The Problem
                                                Passkeys are typically synced across your devices via your cloud account (e.g., Apple ID/iCloud Keychain or Google Password Manager). If a user loses their device but still has their Apple ID, they can simply sign in on a new device and their Passkey will be there.

                                                However, if a user loses access to their **entire account ecosystem** (e.g., losing access to their Apple ID or Google Account) or switches to a new ecosystem entirely, they lose their private key (Passkey) and cannot log in.

                                                ### The Solution: Biometric Identity Verification (Persona + Email)
                                                We use a "Defense in Depth" strategy. Access to the email inbox alone is **not sufficient** to recover the account. The user must prove their identity using **Biometric Verification** via our identity partner, **Persona**.

                                                #### Recovery Workflow:
                                                1.  **Initiation:**
                                                    - User clicks **"Lost Access to My Account"** or **"Reset Passkey Access"** on the login screen.
                                                    - **Identity Challenge:** The app launches a **Persona Inquiry** flow.

                                                2.  **Biometric Scan (Face Match):**
                                                    - **Liveness Detection:** The user is prompted to take a live selfie. Persona analyzes the video feed to ensure the user is a real person present at that moment (preventing spoofing with photos or screens).
                                                    - **1:1 Match:** Persona compares the new biometric data against the original KYC identity profile established during sign-up to confirm it is the same person.
                                                    - **Result:** Persona sends a secure webhook to our backend confirming the identity match.

                                                3.  **Email Dispatch:**
                                                    - Once identity is biometrically verified, the system sends a **One-Time Password (OTP)** or **Magic Link** to the *email address on file*.
                                                    - *Note:* This ensures the user controls both the biological identity AND the communication channel.

                                                4.  **Access Restoration & Security Reset:**
                                                    - User clicks the link or enters the OTP.
                                                    - **Automatic Security Purge:** Upon successful verification, the system **IMMEDIATELY DELETES ALL EXISTING PASSKEYS** associated with the account.
                                                    - This ensures that if the old device was stolen, it is now useless for accessing the account.

                                                5.  **Re-Enrollment (Mandatory):**
                                                    - The user is placed in a restricted "Recovery Session".
                                                    - The only allowed action is **Register New Passkey**.
                                                    - Once the new passkey is created, full account access is restored.

                                                ---

                                                ## 5. Security Considerations
                                                - **Biometric Privacy:** We do not store raw biometric data. All matching is handled securely by Persona.
                                                - **Rate Limiting:** The recovery endpoint is strictly rate-limited.
                                                - **Notification:** A security alert is sent to the email address notifying them that a recovery was initiated.
                                                - **Mocking:** For development, the email sending service is mocked (logs the OTP to the console).
                                                """,
                "v2",
                "security",
                "low",
                true));

        // Chunk 14 — ACATS Transfers (V2)
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "acats_transfers_v2",
                "Finding Account Number & Transferring Assets (ACATS)",
                """
                        # Finding Account Number & Transferring Assets (ACATS)

                        ## Finding your Account Number
                        Your **FRED Account Number** (Alpaca Account Number) can be found in the app by navigating to:
                        **Settings > Account Security**

                        It is located in the "Identity & Contact" section, labeled "Alpaca Account Number".

                        ## Transferring Assets to Another Brokerage (ACATS)
                        FRED believes in complete liquidity and optionality. Your assets are held by **Alpaca Securities**, a standard FINRA-registered brokerage.
                        This means they are fully **portable** to other major brokerages (like Fidelity, Schwab, Vanguard, or Robinhood) at any time.

                        ### How to Transfer (The "Pull" Process)
                        ACATS transfers are always initiated by the **receiving** brokerage (the place you are moving TO).

                        1.  **Open an account** at your new brokerage.
                        2.  Look for their **"Transfer Assets"** or **"ACATS"** option.
                        3.  Select **"Alpaca Securities"** as the current brokerage.
                        4.  Enter your **FRED Account Number** (found in **Settings > Account Security**).
                        5.  Select **"Full Transfer"**.
                        Your new brokerage will then contact Alpaca and "pull" your assets over.
                        """,
                "v2",
                "liquidity",
                "low",
                true));

        // Chunk 15 — ACH Transfer Timing (V2)
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "ach_transfer_timing_v2",
                "Investment Date vs. Trade Execution",
                """
                        # Investment Date vs. Trade Execution

                        ## Understanding ACH Transfer Timing
                        When you set up recurring investments in FRED, the date you select (like your payday) is the **ACH Transfer Initiation Date**, not the actual trade execution date.

                        **What happens:**
                        1. **Transfer Initiation**: On your selected date, FRED initiates an ACH transfer from your bank account
                        2. **ACH Clearing**: The transfer takes **1-3 business days** to clear (weekends and holidays don't count)
                        3. **Trade Execution**: Once the funds clear and the market is open, your investments are purchased

                        ## Example Timeline
                        If you set your investment date to the **15th** (your payday):
                        - **15th**: ACH transfer is initiated from your bank account
                        - **16th-18th**: Transfer clears (1-3 business days)
                        - **17th-18th**: Investments are purchased once funds are available

                        ## Why This Timing?
                        This is industry-standard behavior for all investing apps:
                        - **Safety**: Ensures funds are actually available before trading
                        - **Compliance**: Prevents failed trades and overdrafts
                        - **Reliability**: Follows standard ACH clearing times

                        ## What You'll See
                        - **Transfer Notification**: Email when ACH transfer is initiated on your selected date
                        - **Trade Confirmation**: Email when investments are actually purchased (1-3 days later)

                        This timing protects you from failed transfers and ensures your investments happen smoothly.
                        """,
                "v2",
                "platform",
                "low",
                true));

        // Chunk 16 — Metrics Explanation (V2)
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "metrics_pl_explanation_v2",
                "Total Cumulative Pre-tax Return",
                """
                        # Understanding Your Dashboard Metrics

                        FRED displays your **Total Cumulative Pre-tax Return** as a percentage at the bottom of your chart. here is what it means:

                        **Total Cumulative Pre-tax Return**
                        - This percentage represents your **TOTAL** historical performance since you started with FRED.
                        - It accounts for both **Unrealized** gains (from assets you currently hold) AND **Realized** profits/losses (from assets you have already sold).
                        - This is the true measure of your account's efficiency and growth over time, capturing the complete picture of every dollar invested.

                        unlike a simple daily change, this number tells you: "For every dollar I've put in since day one, how much has it grown?"
                        """,
                "v2",
                "metrics",
                "low",
                true));

        // Chunk 17 — Why Fred Wears The Suit (V2)
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "fred_clothing_v2",
                "Why Fred Wears The Suit (The Uniform)",
                """
                        People ask me about the suit. They wonder why a pig who fought so hard for freedom still dresses like he belongs in a corporate pen.

                        The honest answer is that I did not choose this look. The corporate world put me in it.

                        Pigs are not meant for suits. We are meant for open fields, afternoon naps, and the gentle pleasure of doing nothing on a warm day. But the 9-to-5 had other plans. For years, this gray suit was the price of entry: show up, sit down, look the part, collect the paycheck. It was the uniform of the sty, the one they built to keep me penned in and productive.

                        I keep wearing it now as a reminder.

                        A reminder that freedom is not about what you wear. It is about who controls your time.

                        Back then, my days were funded by a boss, a schedule, and this suit. Now they are funded by my portfolio. That is the only difference that matters.

                        When your money works so you do not have to, you can wear the uniform, but you are no longer trapped inside it.

                        That is what freedom looks like on a pig.
                        """,
                "v2",
                "philosophy",
                "low",
                true));

        // Chunk 18 — FRED Story Math Validation (V2)
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "fred_story_math_validation_v2",
                "Is FRED's $2.37 Million Story Realistic?",
                """
                        # Is FRED's $2.37 Million Story Realistic?

                        Yes, absolutely. Here's the math behind FRED's journey from $0 to $2.37 million.

                        ## The Numbers

                        • **Contributions:** $936,000 over 18 years ($2,000 every 2 weeks)
                        • **Final portfolio value:** $2,370,000
                        • **Total gain:** $1,434,000 (153% return on contributions)

                        ## What Annual Return Does This Require?

                        With regular bi-weekly contributions of $2,000, growing to $2.37M over 18 years implies an **annualized return of approximately 8-9%**.

                        ## Historical Context

                        This outcome is **realistic and actually conservative** compared to historical market returns:
                        • The **S&P 500** has averaged around **10% annually** over the long term (including dividends)
                        • A **diversified 60/40 portfolio** (stocks/bonds) has historically returned **7-9% annually**
                        • Even **conservative 80/20 portfolios** have averaged **8-10%** over decades

                        ## Assumptions Behind This Outcome

                        The 8-9% implied return assumes:
                        • A well-diversified portfolio (like FRED's default)
                        • Dividend reinvestment
                        • Staying invested through market ups and downs
                        • Not trying to time the market

                        ## The Power of Compounding

                        Most of that $1.43M in gains came from **compound growth in the later years**. That's why consistency over 18 years is so powerful: you're not just earning returns on your contributions, but **returns on your returns**.

                        This is exactly why FRED's story resonates: it's **boring, realistic, and achievable** with discipline.

                        *Disclaimer: This story is a hypothetical illustration based on historical market data. Past performance does not guarantee future results. Investing involves risk, including the loss of principal.*
                        """,
                "v2",
                "philosophy",
                "low",
                true));

        // Chunk - FAQ: What is FRED doing exactly?
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "faq_fred_function_v1",
                "FAQ: What is FRED doing exactly?",
                "FRED is your automated investment pilot designed to buy you time back in your life by helping you retire earlier than you would have otherwise. " +
                "We handle the complex logic of recurring trades and navigating optimal retirement strategy planning. " +
                "Additionally, FRED acts as a resource available to answer your questions about early retirement planning and its dependency on long term investing. " +
                "We partner with Plaid and Alpaca Securities to securely hold your assets and execute the actual trades, ensuring your money is handled by a regulated custodian.",
                "v1",
                "faq",
                "low",
                true));

        // Chunk - FAQ: Is my money safe?
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "faq_money_safety_v1",
                "FAQ: Is my money safe?",
                "Your brokerage account is held with Alpaca Securities LLC, a member of SIPC, which protects securities customers of its members up to $500,000 (including $250,000 for claims for cash). " +
                "This is the same protection limit provided by other major brokerages like Fidelity and Schwab. FRED uses bank-level encryption to secure your data. " +
                "Short answer: Yes, your money is safe.",
                "v1",
                "faq",
                "low",
                true));

        // Chunk - FAQ: When will my investments go through?
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "faq_investment_timing_v1",
                "FAQ: When will my investments go through?",
                "The ACH transfer is initiated on your Transfer Date. Once it completes (typically 2-3 business days later), the recurring purchase orders for your current portfolio are placed during market hours.",
                "v1",
                "faq",
                "low",
                true));

        // Chunk - FAQ: Does the schedule keep my selected day?
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "faq_schedule_consistency_v1",
                "FAQ: Does the schedule keep my selected day?",
                "Yes! We always aim to execute on your exact preferred day. If that day falls on a weekend or market holiday, we simply shift that specific investment to the next available business day. Your future investments will stay on your original schedule.",
                "v1",
                "faq",
                "low",
                true));

        // Chunk - FAQ: What type of account do I get with Alpaca through FRED?
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "faq_account_type_v1",
                "FAQ: What type of account do I get with Alpaca through FRED?",
                "You have a personal brokerage account with Alpaca Securities, registered entirely in your name. This gives you full ownership and control over the account. At any point, you can liquidate your holdings or transfer your assets to another brokerage service.",
                "v1",
                "faq",
                "low",
                true));

        // Chunk - FAQ: How do taxes work and how do I report them?
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "faq_taxes_reporting_v1",
                "FAQ: How do taxes work and how do I report them?",
                "As with any brokerage account, you are responsible for reporting capital gains and dividends. We (via our partner Alpaca) will generate a consolidated Form 1099 by mid-February each year. You can download this document directly from the app to use for your tax filing.",
                "v1",
                "faq",
                "low",
                true));

        // Chunk - FAQ: What do the different portfolio values mean?
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "faq_portfolio_values_v1",
                "FAQ: What do the different portfolio values mean?",
                "Total Equity is your complete account value (Portfolio Value + Buying Power). " +
                "Portfolio Value is your stock holdings' market value (Total Invested + Total G/L). " +
                "Total Invested is the total amount of money you have contributed to your portfolio. " +
                "Buying Power is funds available for immediate trading. " +
                "Settled Cash is withdrawn-ready funds (typically available 1 day after selling stocks).",
                "v1",
                "faq",
                "low",
                true));

        // Chunk - FAQ: What do the Portfolio Insight table columns mean?
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "faq_portfolio_insights_v1",
                "FAQ: What do the Portfolio Insight table columns mean?",
                "Symbol shows a stock ticker and company name. " +
                "Quantity is how many shares you own. " +
                "Avg Cost is the average price you paid per share. " +
                "Current is today's market price per share. " +
                "Value is your position's total market value (Quantity × Current). " +
                "Day G/L shows today's profit or loss. " +
                "Total G/L shows your total profit or loss since buying. " +
                "% Account shows what percentage of your total portfolio this position represents.",
                "v1",
                "faq",
                "low",
                true));

        // Chunk - FAQ: How long for buying power to become settled cash?
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "faq_settled_cash_v1",
                "FAQ: How long for buying power to become settled cash?",
                "Typically, trade settlement takes 1 business day after the sell date (T+1). Once settled, the funds become \"Available to Withdraw\".",
                "v1",
                "faq",
                "low",
                true));

        // Chunk — Financial Freedom Calculations
        CANONICAL_CHUNKS.add(new CanonicalChunk(
                "financial_freedom_calculations_v1",
                "Financial Freedom Calculations: Dashboard Badge vs MFU Days Bought Back",
                """
                        # Financial Freedom Calculations

                        FRED uses two different "financial freedom" calculations in different parts of the app. They measure different things and will show different numbers. This is intentional.

                        ## Target Retirement Income Is a MONTHLY Number
                        The target retirement income the user sets in their investor profile is a MONTHLY number: what they want to spend per month in retirement. ALWAYS describe it to the user as a monthly figure (for example "$8,400/mo"), never as an annual number. Internally the app stores it annualized (monthly × 12), but that is an implementation detail the user never sees.

                        ## 1. Dashboard Freedom Badge (Portfolio Dashboard)
                        **Location:** Hero card on the portfolio dashboard
                        **What it measures:** Simple spending runway. It shows how long your current equity could sustain your lifestyle if you stopped working today.

                        ### Formula
                        monthlySpending = target retirement income (the monthly number from the investor profile)
                        dailyExpenses = (monthlySpending × 12) / 365
                        totalDays = equity / dailyExpenses

                        ### Display Rules
                        - 1-99 days → "worth X days of $Yk/mo (est.)"
                        - 100+ days (converted to months) → "worth X months of $Yk/mo (est.)"
                        - 24+ months (converted to years) → "worth X years of $Yk/mo (est.)"
                        ($Yk/mo is the user's monthly target retirement income.)

                        ### Example
                        If equity = $10,000 and target retirement income = $6,000/mo:
                        dailyExpenses = ($6,000 × 12) / 365 = $197.26/day
                        totalDays = $10,000 / $197.26 = 50 days
                        Badge shows: "worth 50 days of $6k/mo (est.)"

                        ## 2. MFU Days Bought Back (Monthly Freedom Update)
                        **Location:** Monthly Freedom Update emails and progress tracking
                        **What it measures:** How many days your investments have bought you ahead of a typical retirement age, accounting for compound growth over time.

                        ### Formula
                        Uses Future Value of Annuity with compound growth:
                        monthlyRate = 0.10 / 12 ≈ 0.00833 (assumes 10% annual return)
                        monthlyInvestment = converted from user's frequency (weekly, biweekly, monthly)
                        futureValue = monthlyInvestment × ((1 + monthlyRate)^months - 1) / monthlyRate
                        monthsToTarget = months needed until futureValue >= retirementIncome
                        daysBoughtBack = (monthsToTarget_without_this_month - monthsToTarget_with_this_month) × 30

                        ### Why It Is Different
                        The MFU calculation projects forward using compound growth assumptions. It answers: "How many days ahead of a typical retirement age have my investments moved me?" The dashboard badge answers: "If I stopped everything right now, how long could I live off my equity?"

                        ## Why Both Exist
                        - The **dashboard badge** gives a grounded, tangible snapshot with no assumptions about future returns.
                        - The **MFU days bought back** motivates by showing the compounding power of each contribution over time.
                        - They are intentionally different metrics serving different psychological purposes.
                        """,
                "v2",
                "metrics",
                "low",
                true));
    }
}
