import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { BehaviorSubject } from 'rxjs';
import { GoalsService } from './goals.service';
import { Goal, GoalWrite, LegacyGoalV2 } from '../models/goal.model';
import { AuthService } from './auth.service';
import { DeviceIdService } from './device-id.service';
import { JwtTokenUtils } from '../utils/jwt-token.utils';
import { environment } from '../../environments/environment';

describe('GoalsService', () => {
  let service: GoalsService;
  let http: HttpTestingController;
  let loggedIn$: BehaviorSubject<boolean>;
  const url = `${environment.backendApiUrl}/goals`;

  function goal(id: number, name = 'Goal ' + id): Goal {
    return {
      id,
      clientId: null,
      type: 'CUSTOM',
      name,
      icon: 'flag',
      config: { targetAmount: 10000, targetYear: 2030 },
      currentBalance: 0,
      balanceUpdatedAt: null,
      anchorBalance: 0,
      anchorMonthly: 0,
      planRate: 0.07,
      planRateKind: 'market',
      planAnchoredAt: '2026-11-03T00:00:00',
      completed: false,
      sortOrder: id,
      createdAt: '2026-11-03T00:00:00',
      updatedAt: '2026-11-03T00:00:00',
    };
  }

  function write(name = 'New goal'): GoalWrite {
    return {
      type: 'CUSTOM',
      name,
      icon: 'flag',
      config: { targetAmount: 10000, targetYear: 2030 },
      currentBalance: 0,
      completed: false,
      anchorMonthly: 0,
      planRate: 0.07,
      planRateKind: 'market',
    };
  }

  beforeEach(() => {
    localStorage.setItem('userId', '40');
    spyOn(JwtTokenUtils, 'getValidJwtToken').and.returnValue('jwt-token');
    loggedIn$ = new BehaviorSubject<boolean>(true);
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        { provide: AuthService, useValue: { isLoggedIn$: loggedIn$ } },
        { provide: DeviceIdService, useValue: { getDeviceId: () => 'device-1' } },
      ],
    });
    service = TestBed.inject(GoalsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.removeItem('userId');
    localStorage.removeItem('fred.goals.v2.40');
  });

  it('load GETs /goals with a bearer header and sets the signal', () => {
    let result: Goal[] | undefined;
    service.load().subscribe(r => (result = r));

    const req = http.expectOne(url);
    expect(req.request.method).toBe('GET');
    expect(req.request.headers.get('Authorization')).toMatch(/^Bearer /);
    expect(req.request.headers.get('X-Device-ID')).toBe('device-1');
    req.flush([goal(1), goal(2)]);

    expect(result?.length).toBe(2);
    expect(service.goals().length).toBe(2);
    expect(service.loaded()).toBeTrue();
  });

  it('load reuses the cache inside the TTL and refresh ignores it', () => {
    service.load().subscribe();
    http.expectOne(url).flush([goal(1)]);

    service.load().subscribe();
    http.expectNone(url);

    service.refresh().subscribe();
    http.expectOne(url).flush([goal(1), goal(2)]);
    expect(service.goals().length).toBe(2);
  });

  it('load is a no-op without a userId', () => {
    localStorage.removeItem('userId');
    let result: Goal[] | undefined;
    service.load().subscribe(r => (result = r));

    http.expectNone(url);
    expect(result).toEqual([]);
  });

  it('create POSTs and appends to the signal', () => {
    service.goals.set([goal(1)]);
    let created: Goal | undefined;
    service.create(write('Second')).subscribe(g => (created = g));

    const req = http.expectOne(url);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Authorization')).toMatch(/^Bearer /);
    expect(req.request.body.name).toBe('Second');
    req.flush(goal(2, 'Second'));

    expect(created?.id).toBe(2);
    expect(service.goals().map(g => g.id)).toEqual([1, 2]);
  });

  it('update PUTs and replaces the goal in the signal', () => {
    service.goals.set([goal(1), goal(2)]);
    service.update(2, write('Renamed')).subscribe();

    const req = http.expectOne(`${url}/2`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.name).toBe('Renamed');
    req.flush(goal(2, 'Renamed'));

    expect(service.goals().map(g => g.name)).toEqual(['Goal 1', 'Renamed']);
  });

  it('remove DELETEs and drops the goal', () => {
    service.goals.set([goal(1), goal(2)]);
    service.remove(1).subscribe();

    const req = http.expectOne(`${url}/1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(service.goals().map(g => g.id)).toEqual([2]);
  });

  it('importLegacy POSTs /goals/import with clientId from the v2 id', () => {
    const legacy: LegacyGoalV2[] = [{
      id: 'uuid-1',
      name: 'Old house',
      icon: 'home',
      targetAmount: 50000,
      targetYear: 2032,
      startingValue: 1000,
      perPaycheckOutsideFRED: 25,
      completed: false,
    }];
    service.importLegacy(legacy).subscribe();

    const req = http.expectOne(`${url}/import`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      goals: [{
        clientId: 'uuid-1',
        name: 'Old house',
        icon: 'home',
        targetAmount: 50000,
        targetYear: 2032,
        startingValue: 1000,
        completed: false,
      }],
    });
    req.flush([goal(7, 'Old house')]);

    expect(service.goals().map(g => g.id)).toEqual([7]);
  });

  it('readLegacyV2 tolerates corrupt storage', () => {
    localStorage.setItem('fred.goals.v2.40', '{not json');
    expect(service.readLegacyV2('40')).toEqual([]);

    localStorage.setItem('fred.goals.v2.40', JSON.stringify({ not: 'an array' }));
    expect(service.readLegacyV2('40')).toEqual([]);

    const good = {
      id: 'a', name: 'n', icon: 'flag', targetAmount: 1, targetYear: 2030,
      startingValue: 0, perPaycheckOutsideFRED: 0, completed: false,
    };
    localStorage.setItem('fred.goals.v2.40', JSON.stringify([good, { id: 5 }, null]));
    expect(service.readLegacyV2('40')).toEqual([good]);

    service.clearLegacyV2('40');
    expect(localStorage.getItem('fred.goals.v2.40')).toBeNull();
  });

  it('a GET in flight during a create does not drop the created goal', () => {
    service.goals.set([goal(1)]);
    service.load().subscribe();
    const get = http.expectOne(r => r.method === 'GET' && r.url === url);

    service.create(write('Second')).subscribe();
    http.expectOne(r => r.method === 'POST' && r.url === url).flush(goal(2, 'Second'));
    get.flush([goal(1)]);

    expect(service.goals().map(g => g.id)).toEqual([1, 2]);
  });

  it('a GET that lands after logout does not repopulate', () => {
    service.load().subscribe();
    const get = http.expectOne(url);

    loggedIn$.next(false);
    get.flush([goal(1), goal(2)]);

    expect(service.goals()).toEqual([]);
    expect(service.loaded()).toBeFalse();
  });

  it('create and importLegacy do not append an id already present', () => {
    service.goals.set([goal(1), goal(2)]);
    service.create(write('Second')).subscribe();
    http.expectOne(url).flush(goal(2, 'Second'));
    expect(service.goals().map(g => g.id)).toEqual([1, 2]);

    service.importLegacy([]).subscribe();
    http.expectOne(`${url}/import`).flush([goal(2), goal(3)]);
    expect(service.goals().map(g => g.id)).toEqual([1, 2, 3]);
  });

  it('clears memory on logout', () => {
    service.goals.set([goal(1)]);
    service.loaded.set(true);

    loggedIn$.next(false);

    expect(service.goals()).toEqual([]);
    expect(service.loaded()).toBeFalse();
  });
});
