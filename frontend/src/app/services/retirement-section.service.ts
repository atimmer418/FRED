import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/**
 * Deep-links the tab2 retirement screen to one of its sections. The retirement-planning component
 * mounts once inside the swipeable pager and its section is internal state, so an outside caller
 * (e.g. the MFU Retire card, FRED-217) asks for a section through this service instead of a route.
 * Mirrors TabActivationService: the pager move and the section switch are two small requests.
 */
@Injectable({ providedIn: 'root' })
export class RetirementSectionService {
  private readonly requests = new Subject<'simulator' | 'education'>();
  readonly request$: Observable<'simulator' | 'education'> = this.requests.asObservable();

  requestSection(section: 'simulator' | 'education'): void {
    this.requests.next(section);
  }
}
