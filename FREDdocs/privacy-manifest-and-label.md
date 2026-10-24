# The privacy manifest and the App Store label

Apple asks the same question twice, in two places that must agree:

- **`PrivacyInfo.xcprivacy`**, in the app bundle, read by Apple's tooling at submission.
  It lives at `frontend/ios/App/App/PrivacyInfo.xcprivacy`.
- **The App Store privacy label**, answered by hand in App Store Connect, shown to a person
  deciding whether to download the app.

A build without a manifest is rejected. A label that contradicts the manifest is worse than
either: it is the kind of disagreement that reads as carelessness about the exact thing the
question is asking about.

This page is the answer sheet for both, and the reason for each answer. **Last reviewed:**
2026-10-24. **Owner:** Andrew Timmer.

---

## What the manifest declares today

| Field | Value | Why |
|---|---|---|
| `NSPrivacyTracking` | **false** | Nothing is shared with a data broker and nothing is joined to third-party data to target anyone. There is no advertising SDK and no attribution SDK in the app. |
| `NSPrivacyTrackingDomains` | **empty** | Follows from the above. A non-empty list here would require App Tracking Transparency. |
| Product interaction | collected, **not linked**, not for tracking, purpose **analytics** | Which screens were reached, which steps were completed, which controls were used. |
| User ID | collected, **not linked**, not for tracking, purpose **analytics** | The opaque `public_id` UUID. |
| `NSPrivacyAccessedAPITypes` | UserDefaults, reason **CA92.1** | Capacitor's bridge keeps preferences in UserDefaults and the WebView's local storage sits behind it. CA92.1 is "information from the app itself", which is the only use here. The Capacitor pods do ship manifests, but both declare an **empty** `NSPrivacyAccessedAPITypes`, so if the app does not declare this, nobody does. FRED's own Swift code never touches UserDefaults. |

### Why "not linked" is the honest answer

Apple's "linked to the user" means the data is associated with an identity. The only identifier
analytics sends is `public_id`: a random UUID (RFC 4122 v4) generated for analytics and nothing
else. It is not the database row id, not the email address, not the broker account id, and not
derived from any of them. Outside our own database it cannot be turned into a person.

The other half of the answer is what is **not** sent: no name, email, phone, address, date of
birth, Social Security number or any part of one, no account or routing number, no institution
name, no free text, and no dollar amount (every amount is a band such as `1k_2499`). The full
list is in [what-we-send.md](what-we-send.md), and the code is held to it by a deny-list and a
value-shape filter on both the client and the server, with a test on each side that fires a
forbidden payload and proves it never leaves.

### What would make it false

**Billing.** The day a revenue or purchase property is attached to a person, or a subscription
event starts carrying a tier against a payer, "purchase history" becomes a collected data type
and the "not linked" answer has to be re-argued rather than assumed. Billing does not exist in
this repository; a separate session owns it. That session inherits this paragraph.

**Acquisition attribution.** If a campaign or referrer is ever attached to a person record, that
is still not tracking as Apple defines it, as long as it is not joined to third-party data. The
`NSPrivacyTracking` answer should be re-read anyway if an attribution SDK is ever added, because
an SDK is exactly what changes the answer.

**Sentry.** Not integrated. A crash reporter collects "crash data" and often more by default, so
it needs its own row here and in `what-we-send.md` **before** a key is configured.

---

## What is NOT declared yet, and who owns it

The manifest covers analytics. It does **not** cover the data the rest of the app collects, and
that has to be declared before any submission. This is listed rather than quietly omitted.

| Data type | Where it is collected | Owner |
|---|---|---|
| Name, date of birth, address | the KYC form | the KYC flow |
| **Social Security number** (`NSPrivacyCollectedDataTypeOtherDataTypes`, sensitive) | the KYC form | the KYC flow |
| Email address | registration and account recovery | auth |
| Phone number | the KYC form, where collected | the KYC flow |
| Bank account details | the Plaid link | banking |
| Other financial info (portfolio, contributions) | the app's own screens | investing |

None of it goes to analytics; all of it goes to our own servers and, for KYC, to the broker. The
manifest and the label still have to say it is collected, with a purpose of "app functionality",
and to say whether it is linked to the user, which for all of the above it is.

**Before the first submission, someone has to add those entries.** The analytics work does not own
them and should not guess at their purposes.

---

## The label answers, for App Store Connect

Answer these to match the manifest exactly:

1. **Do you collect data?** Yes.
2. **Identifiers:** User ID. Used for **analytics** and **app functionality**. **Not** linked to
   the user's identity. **Not** used for tracking.
3. **Usage Data:** Product Interaction. Used for **analytics**. **Not** linked. **Not** used for
   tracking.
4. **Diagnostics:** nothing, while Sentry is absent. Revisit the day it lands.
5. **Tracking:** No. The app does not track users, so no ATT prompt is required or shown.
6. Every other category: answered by the KYC, auth and banking owners per the table above, not by
   this work.

### The two things that must also be true in writing

- **The privacy policy must say analytics is on by default and can be turned off.** It is on by
  default, by Andy's decision at Checkpoint C, and the switch is "Share anonymous usage data" in
  Settings. A policy that says it is off by default, or does not mention the switch, contradicts
  the app.
- **The label and the policy must agree with [what-we-send.md](what-we-send.md).** That document
  is written for counsel and is the single description of what leaves; it is the one to send
  outward, and the one to correct first if any of this changes.

---

## Changing any of this

1. Edit `PrivacyInfo.xcprivacy`.
2. Edit this page's table.
3. Edit the label in App Store Connect.
4. If what the code sends changed rather than just how it is described, edit
   [what-we-send.md](what-we-send.md) and [analytics-events.md](analytics-events.md) too, and
   expect `npm run test:events-documented` to fail until the taxonomy and the document agree.

The manifest is a plain XML plist, so `plutil -lint frontend/ios/App/App/PrivacyInfo.xcprivacy`
tells you it is well formed. It is registered in the Xcode project as a bundle resource, which is
what gets it into the build; if it were only on disk, Apple would never see it.
