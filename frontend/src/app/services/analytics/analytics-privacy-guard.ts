import {
  DECLARED_PROPERTY_KEYS,
  PERSON_PROPERTY_KEYS,
  ScalarProps,
  ScalarValue,
} from './analytics-events';

/**
 * The backstop. Nothing identifying reaches the vendor today, and that is enforced by
 * construction: the event payloads are a closed, typed vocabulary with no free text and no raw
 * amount. This file exists for the mistake that construction cannot catch, the one where someone
 * adds a property in good faith and nobody notices what is in it.
 *
 * Two independent checks, because they fail in different directions:
 *
 * - **The key deny-list** catches a property that is named for something forbidden, whatever it
 *   holds. `user_email` goes even if it happens to be empty today.
 * - **The value-shape checks** catch a property whose name says nothing, like `detail` or `ref`,
 *   whose value looks like an email, a phone number, a Social Security number, a long digit run
 *   or a JWT.
 *
 * A violation **strips the one property** rather than dropping the event. A funnel with one
 * missing dimension is still a funnel; losing the event would hide the mistake instead of
 * reporting it. In development the stripping is loud. The value is never logged: printing it to a
 * console is the leak it was stripped to prevent.
 *
 * These checks never throw. The caller's catch exists for the vendor, not for this.
 */

/** One rule, named so a violation says which rule fired rather than printing the value. */
export interface GuardRule {
  readonly name: string;
  readonly pattern: RegExp;
}

/**
 * Property names that may never leave, matched against the key with case and separators removed,
 * so `userEmail`, `user_email`, `USER-EMAIL` and `$email` are all one key to this list.
 *
 * The pattern sources are duplicated in the server's AnalyticsPrivacyGuard and held equal by
 * AnalyticsPrivacyGuardParityTest, which reads this file. Keep the two in step: a key that is
 * forbidden on the phone and allowed on the server is the worse half of no guard at all.
 */
export const FORBIDDEN_KEY_PATTERNS: readonly GuardRule[] = [
  { name: 'email', pattern: /email/ },
  { name: 'phone', pattern: /phone|mobilenumber|telephone/ },
  { name: 'government_id', pattern: /ssn|socialsecurity|taxid|passport|driverslicense/ },
  { name: 'date_of_birth', pattern: /^dob$|dateofbirth|birthdate|birthday/ },
  {
    name: 'person_name',
    pattern: /^name$|firstname|lastname|middlename|fullname|surname|maidenname|legalname|holdername/,
  },
  { name: 'institution', pattern: /institutionname|bankname|employername/ },
  { name: 'network_address', pattern: /^ip$|ipaddress/ },
  { name: 'address', pattern: /address|street|zipcode|postalcode|^zip$|^postal$/ },
  { name: 'bank_account', pattern: /accountnumber|routing|iban|swift|cardnumber|^cvv$/ },
  {
    name: 'credential',
    pattern: /password|passcode|secret|apikey|^key$|authorization|bearer|^jwt$|credential|^cookie$|sessionid|accesstoken|refreshtoken|^token$/,
  },
  { name: 'raw_money', pattern: /amount|balance|income|salary|networth|revenue|^price$|^cost$/ },
  { name: 'location', pattern: /latitude|longitude|^lat$|^lng$|^geo$|coordinates/ },
  // A key ENDING in 'text' is the free-text tell: `rejection_reason_text` was the one that got
  // through the first version of this list, and a free-text rejection reason is exactly the thing
  // decision 7 says must be a fixed vocabulary instead. It also catches `context`, which is no
  // loss: a property called context is free text as well.
  {
    name: 'free_text',
    pattern: /^note$|^notes$|comment|message|description|feedback|searchterm|^query$|rawtext|freetext|text$/,
  },
];

/**
 * Value shapes that may never leave, whatever the property is called.
 *
 * `long_digit_run` is the broad one and the reason the others can be precise: an unformatted
 * Social Security number, a bare phone number, an account number and a broker id are all long
 * runs of digits. The named narrow rules sit on top of it so a violation report says what it
 * looked like.
 *
 * Deliberately not case-folded. A JWT begins with the literal `eyJ`, because that is what a
 * base64 `{"` is, and folding the value to compare it would lose that.
 */
export const FORBIDDEN_VALUE_PATTERNS: readonly GuardRule[] = [
  { name: 'email', pattern: /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/ },
  { name: 'ssn_formatted', pattern: /[0-9]{3}-[0-9]{2}-[0-9]{4}/ },
  { name: 'phone_formatted', pattern: /[(]?[0-9]{3}[)]?[ .-][0-9]{3}[ .-][0-9]{4}/ },
  { name: 'phone_e164', pattern: /[+][0-9]{10,15}/ },
  { name: 'long_digit_run', pattern: /[0-9]{8,}/ },
  { name: 'jwt', pattern: /eyJ[a-zA-Z0-9_=-]{8,}\.[a-zA-Z0-9_=-]{8,}/ },
];

/**
 * Keys the value-shape checks skip, and why this is not a hole.
 *
 * Everything the vendor's SDK adds by itself is named with a leading `$`, and several of those are
 * long digit runs by nature: `$time` is an epoch, `$device_id` and `$session_id` are generated
 * ids, `$screen_height` is a number. None of them can carry anything of ours, because we did not
 * write them. Running the digit-run rule over them would strip the vendor's own session stitching
 * and tell us nothing.
 *
 * The **key** deny-list still applies to those keys, which is the half that matters: a `$email`
 * or a `$phone` is stripped by name, whoever set it. And the URL-shaped `$` keys are scrubbed
 * before this guard runs, by the service's URL pass, because a route id is not a value shape.
 */
export function isVendorGeneratedKey(key: string): boolean {
  return key.startsWith('$');
}

/** Lowercased, with every separator removed: the form the key deny-list is written against. */
export function normaliseKey(key: string): string {
  return key.toLowerCase().replace(/[^a-z0-9]/g, '');
}

/**
 * Declared taxonomy keys, exempt from the key deny-list.
 *
 * This is the exemption the discovery called for, and it is what makes the deny-list usable:
 * `amount_band` and `monthly_income_band` are the words "amount" and "income", and they are also
 * exactly how money is sent safely. A banded amount is the opposite of a raw one, so the list of
 * keys the taxonomy declares is the list the deny-list must not touch.
 *
 * The exemption is narrow on purpose. It covers the key's NAME only: every declared key's value
 * still goes through the value-shape checks, so a declared property that starts carrying an email
 * is stripped like any other.
 */
const EXEMPT_KEYS: ReadonlySet<string> = new Set(
  [...DECLARED_PROPERTY_KEYS, ...PERSON_PROPERTY_KEYS].map(normaliseKey),
);

export function isDeclaredTaxonomyKey(key: string): boolean {
  return EXEMPT_KEYS.has(normaliseKey(key));
}

/** The rule a key breaks, or null when the key is allowed. */
export function forbiddenKeyRule(key: string): GuardRule | null {
  if (isDeclaredTaxonomyKey(key)) {
    return null;
  }
  const normalised = normaliseKey(key);
  return FORBIDDEN_KEY_PATTERNS.find((rule) => rule.pattern.test(normalised)) ?? null;
}

/** The rule a value breaks, or null when the value is allowed. */
export function forbiddenValueRule(key: string, value: ScalarValue): GuardRule | null {
  if (isVendorGeneratedKey(key) || value === null || typeof value === 'boolean') {
    return null;
  }
  // Numbers are checked as their decimal text, so a nine-digit number is caught like a
  // nine-character string. A raw SSN arrives as either, depending on who typed the property.
  const text = typeof value === 'number' ? String(value) : value;
  return FORBIDDEN_VALUE_PATTERNS.find((rule) => rule.pattern.test(text)) ?? null;
}

/** What was stripped, for the caller to report. Carries the rule, never the value. */
export interface GuardViolation {
  readonly key: string;
  readonly rule: string;
  readonly kind: 'key' | 'value';
}

/**
 * Strips every forbidden property and reports what went.
 *
 * Returns a new object; the input is never mutated, because the vendor keeps its own reference to
 * the payload it handed us.
 */
export function applyPrivacyGuard(props: ScalarProps): {
  props: ScalarProps;
  violations: GuardViolation[];
} {
  const kept: ScalarProps = {};
  const violations: GuardViolation[] = [];

  for (const [key, value] of Object.entries(props)) {
    const keyRule = forbiddenKeyRule(key);
    if (keyRule) {
      violations.push({ key, rule: keyRule.name, kind: 'key' });
      continue;
    }
    const valueRule = forbiddenValueRule(key, value);
    if (valueRule) {
      violations.push({ key, rule: valueRule.name, kind: 'value' });
      continue;
    }
    kept[key] = value;
  }

  return { props: kept, violations };
}
