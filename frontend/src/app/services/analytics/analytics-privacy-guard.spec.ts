import {
  applyPrivacyGuard,
  forbiddenKeyRule,
  forbiddenValueRule,
  isDeclaredTaxonomyKey,
  normaliseKey,
} from './analytics-privacy-guard';
import { DECLARED_PROPERTY_KEYS, PERSON_PROPERTY_KEYS } from './analytics-events';

describe('the analytics privacy guard', () => {
  describe('the key deny-list', () => {
    const forbidden: Array<[string, string]> = [
      ['email', 'email'],
      ['user_email', 'email'],
      ['userEmail', 'email'],
      ['$email', 'email'],
      ['USER-EMAIL', 'email'],
      ['phone', 'phone'],
      ['phone_number', 'phone'],
      ['ssn', 'government_id'],
      ['ssn_last_four', 'government_id'],
      ['social_security_number', 'government_id'],
      ['tax_id', 'government_id'],
      ['dob', 'date_of_birth'],
      ['date_of_birth', 'date_of_birth'],
      ['name', 'person_name'],
      ['first_name', 'person_name'],
      ['last_name', 'person_name'],
      ['institution_name', 'institution'],
      ['bank_name', 'institution'],
      ['street_address', 'address'],
      ['zip', 'address'],
      ['postal_code', 'address'],
      ['account_number', 'bank_account'],
      ['routing_number', 'bank_account'],
      ['password', 'credential'],
      ['access_token', 'credential'],
      ['api_key', 'credential'],
      ['authorization', 'credential'],
      ['amount', 'raw_money'],
      ['contribution_amount', 'raw_money'],
      ['balance', 'raw_money'],
      ['annual_income', 'raw_money'],
      ['ip', 'network_address'],
      ['ip_address', 'network_address'],
      ['latitude', 'location'],
      ['note', 'free_text'],
      ['user_comment', 'free_text'],
      ['error_message', 'free_text'],
      ['rejection_reason_text', 'free_text'],
      ['reason_text', 'free_text'],
    ];

    forbidden.forEach(([key, rule]) => {
      it(`refuses '${key}' as ${rule}`, () => {
        expect(forbiddenKeyRule(key)?.name).toBe(rule);
      });
    });

    it('allows a key that is merely unfamiliar, so the list cannot become an allow-list by accident', () => {
      // The guard is a deny-list by design. A new property name is the author's responsibility and
      // the doc check's; this file only refuses what is named for something forbidden.
      expect(forbiddenKeyRule('wiggle_count')).toBeNull();
      expect(forbiddenKeyRule('$os')).toBeNull();
      expect(forbiddenKeyRule('$device_type')).toBeNull();
    });

    it('reduces a key to letters and digits before matching', () => {
      expect(normaliseKey('$initial_current_URL')).toBe('initialcurrenturl');
    });
  });

  /**
   * Decision 12. These two are the whole reason the exemption exists: the words the deny-list has
   * to forbid are the same words the taxonomy uses for the safe, banded version of the same fact.
   */
  describe('the declared-taxonomy exemption (decision 12)', () => {
    it('lets a declared key through although its name matches a forbidden pattern', () => {
      expect(isDeclaredTaxonomyKey('monthly_income_band')).toBeTrue();
      expect(forbiddenKeyRule('monthly_income_band')).toBeNull();
      expect(forbiddenKeyRule('monthly_invest_band')).toBeNull();
      expect(forbiddenKeyRule('target_band')).toBeNull();
    });

    it('strips an UNDECLARED key that matches the same pattern', () => {
      // The same word, one letter off the declared name, is exactly the mistake this catches.
      expect(isDeclaredTaxonomyKey('monthly_income')).toBeFalse();
      expect(forbiddenKeyRule('monthly_income')?.name).toBe('raw_money');
      expect(forbiddenKeyRule('income_band_v2')?.name).toBe('raw_money');
    });

    it('exempts the key name only, never the value', () => {
      // A declared key whose value turns out to be an email is still stripped. The exemption is
      // about what a property is called, not about trusting what is in it.
      expect(forbiddenValueRule('monthly_income_band', 'someone@example.com')?.name).toBe('email');
    });

    it('covers every declared property key and person property key', () => {
      for (const key of [...DECLARED_PROPERTY_KEYS, ...PERSON_PROPERTY_KEYS]) {
        expect(forbiddenKeyRule(key)).withContext(key).toBeNull();
      }
    });
  });

  describe('the value-shape checks', () => {
    const forbidden: Array<[string, string]> = [
      ['someone@example.com', 'email'],
      ['Someone.Else+tag@sub.example.co.uk', 'email'],
      ['123-45-6789', 'ssn_formatted'],
      ['(555) 123-4567', 'phone_formatted'],
      ['555.123.4567', 'phone_formatted'],
      ['+15551234567', 'phone_e164'],
      ['123456789', 'long_digit_run'],
      ['12345678', 'long_digit_run'],
      ['account 987654321 settled', 'long_digit_run'],
      ['eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0', 'jwt'],
    ];

    forbidden.forEach(([value, rule]) => {
      it(`refuses a value that looks like ${rule}`, () => {
        expect(forbiddenValueRule('detail', value)?.name).toBe(rule);
      });
    });

    it('checks numbers as their decimal text, so a nine-digit number is caught too', () => {
      expect(forbiddenValueRule('detail', 123456789)?.name).toBe('long_digit_run');
      expect(forbiddenValueRule('detail', 7)).toBeNull();
      expect(forbiddenValueRule('sub_step', 2)).toBeNull();
    });

    it('leaves every real taxonomy value alone', () => {
      const real: Array<[string, string | number | boolean | null]> = [
        ['monthly_income_band', '1k_2499'],
        ['monthly_invest_band', '10k_49k'],
        ['years_to_freedom', '10_19'],
        ['step', 'kyc'],
        ['surface', 'freedom_sheet'],
        ['app_environment', 'prod'],
        ['platform', 'ios'],
        ['http_status', 500],
        ['slide_count', 7],
        ['resumed', true],
        ['return_preset', null],
        ['icon', 'home'],
        ['category', 'network'],
      ];
      for (const [key, value] of real) {
        expect(forbiddenValueRule(key, value)).withContext(`${key}=${value}`).toBeNull();
      }
    });

    it('skips the vendor its own generated keys, which are long numbers by nature', () => {
      // $time is an epoch and $device_id is a generated id. Stripping them would break the
      // vendor's session stitching and protect nobody: we did not write them.
      expect(forbiddenValueRule('$time', 1761345678.123)).toBeNull();
      expect(forbiddenValueRule('$device_id', '0192f3a4-8888-7000-9999-111122223333')).toBeNull();
      expect(forbiddenValueRule('$session_id', '01923456789012345678')).toBeNull();
    });

    it('still strips a vendor-shaped key that is named for something forbidden', () => {
      // The skip above is for value shapes only. $email is refused by name, whoever set it.
      const { props, violations } = applyPrivacyGuard({ $email: 'someone@example.com' });
      expect(props).toEqual({});
      expect(violations).toEqual([{ key: '$email', rule: 'email', kind: 'key' }]);
    });
  });

  describe('what it does to a payload', () => {
    it('strips the offending property and keeps the rest of the event', () => {
      const { props, violations } = applyPrivacyGuard({
        step: 'kyc',
        sub_step: 2,
        user_email: 'someone@example.com',
        detail: '123-45-6789',
      });

      expect(props).toEqual({ step: 'kyc', sub_step: 2 });
      expect(violations.map((v) => v.key).sort()).toEqual(['detail', 'user_email']);
    });

    it('does not mutate what it was given', () => {
      const original = { step: 'kyc', email: 'someone@example.com' };
      applyPrivacyGuard(original);
      expect(original.email).toBe('someone@example.com');
    });

    it('reports the rule but never the value, so a console cannot become the leak', () => {
      const { violations } = applyPrivacyGuard({ ssn: '123-45-6789' });
      expect(JSON.stringify(violations)).not.toContain('123-45-6789');
    });
  });
});
