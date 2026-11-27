import { KycDraftUtils } from './kyc-draft.utils';

describe('KycDraftUtils', () => {
  const SSN = '512-34-6789';
  const keys = ['fred_kyc_step1_7001', 'fred_kyc_step1_7002', 'fred_kyc_step1_anon', 'fred_kyc_step1_7003'];
  let savedUserId: string | null;

  beforeEach(() => {
    savedUserId = localStorage.getItem('userId');
    localStorage.setItem('userId', '7001');
    keys.forEach(key => localStorage.removeItem(key));
    localStorage.removeItem('unrelated_key');
  });

  afterEach(() => {
    keys.forEach(key => localStorage.removeItem(key));
    localStorage.removeItem('unrelated_key');
    if (savedUserId === null) {
      localStorage.removeItem('userId');
    } else {
      localStorage.setItem('userId', savedUserId);
    }
  });

  it('save drops the number and keeps the rest', () => {
    KycDraftUtils.save({ givenName: 'Test', dateOfBirth: '01/15/1990', taxId: SSN });

    const stored = localStorage.getItem('fred_kyc_step1_7001')!;
    expect(stored).not.toContain(SSN);
    expect(JSON.parse(stored)).toEqual({ givenName: 'Test', dateOfBirth: '01/15/1990' });
  });

  it('load never hands back a number, and rewrites a draft that had one', () => {
    localStorage.setItem('fred_kyc_step1_7001', JSON.stringify({ givenName: 'Test', taxId: SSN }));

    expect(KycDraftUtils.load()).toEqual({ givenName: 'Test' });
    expect(localStorage.getItem('fred_kyc_step1_7001')).not.toContain(SSN);
  });

  it('load is null with no draft, and with one that is not a form', () => {
    expect(KycDraftUtils.load()).toBeNull();
    localStorage.setItem('fred_kyc_step1_7001', '"just a string"');
    expect(KycDraftUtils.load()).toBeNull();
  });

  it('the launch scrub cleans every account on the phone, not only the signed-in one', () => {
    localStorage.setItem('fred_kyc_step1_7001', JSON.stringify({ givenName: 'One', taxId: SSN }));
    localStorage.setItem('fred_kyc_step1_7002', JSON.stringify({ givenName: 'Two', taxId: '623-45-1234' }));
    localStorage.setItem('fred_kyc_step1_anon', JSON.stringify({ givenName: 'Anon', taxId: SSN }));
    localStorage.setItem('unrelated_key', JSON.stringify({ taxId: 'not ours to touch' }));

    KycDraftUtils.scrubSavedSsns();

    expect(JSON.parse(localStorage.getItem('fred_kyc_step1_7001')!)).toEqual({ givenName: 'One' });
    expect(JSON.parse(localStorage.getItem('fred_kyc_step1_7002')!)).toEqual({ givenName: 'Two' });
    expect(JSON.parse(localStorage.getItem('fred_kyc_step1_anon')!)).toEqual({ givenName: 'Anon' });
    expect(localStorage.getItem('unrelated_key')).toContain('not ours to touch');
  });

  it('the launch scrub removes a draft it cannot read, since it cannot prove it is clean', () => {
    localStorage.setItem('fred_kyc_step1_7003', `{"givenName":"Broken","taxId":"${SSN}"`);

    KycDraftUtils.scrubSavedSsns();

    expect(localStorage.getItem('fred_kyc_step1_7003')).toBeNull();
  });

  it('the launch scrub leaves a clean draft exactly as it was', () => {
    const clean = JSON.stringify({ givenName: 'Test', city: 'Rockville' });
    localStorage.setItem('fred_kyc_step1_7001', clean);

    KycDraftUtils.scrubSavedSsns();

    expect(localStorage.getItem('fred_kyc_step1_7001')).toBe(clean);
  });
});
