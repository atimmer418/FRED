import { RetirementSectionService } from './retirement-section.service';

describe('RetirementSectionService', () => {
  it('emits the requested section to subscribers', () => {
    const svc = new RetirementSectionService();
    const seen: Array<'simulator' | 'education'> = [];
    svc.request$.subscribe(s => seen.push(s));

    svc.requestSection('education');

    expect(seen).toEqual(['education']);
  });
});
