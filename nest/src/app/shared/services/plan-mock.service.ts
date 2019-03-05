/**
 * Copyright 2018, by the California Institute of Technology. ALL RIGHTS RESERVED. United States Government Sponsorship acknowledged.
 * Any commercial use must be negotiated with the Office of Technology Transfer at the California Institute of Technology.
 * This software may be subject to U.S. export control laws and regulations.
 * By accepting this document, the user agrees to comply with all applicable U.S. export laws and regulations.
 * User has the responsibility to obtain export licenses, or other export authority as may be required
 * before exporting such information to foreign countries or providing access to foreign persons
 */

import { Injectable } from '@angular/core';
import { Observable, Observer } from 'rxjs';
import { ActivityInstance, Plan } from '../../../../../schemas';
import { PlanServiceInterface } from './plan-service-interface';

export function getMockActivities(): ActivityInstance[] {
  return [
    {
      activityId: 'SetArrayTrackingMode_25788',
      activityType: 'SetArrayTrackingMode',
      color: '#ffffff',
      constraints: [
        {
          name: 'Latest Start',
          type: 'list',
        },
        {
          name: 'Some Enum State',
          type: 'enum',
        },
        {
          name: 'Geometric Constraint',
          type: 'enum',
        },
      ],
      duration: 580,
      end: 1656459714.184,
      endTimestamp: '2022-179T23:41:54.184',
      intent: '...',
      name: 'SetArrayTrackingMode_FIXED',
      parameters: [
        {
          name: 'Image Resolution',
          type: '',
        },
      ],
      start: 1656459134.184,
      startTimestamp: '2022-179T23:32:14.184',
      y: null,
    },
    {
      activityId: 'SetArrayTrackingMode_25920',
      activityType: 'SetArrayTrackingMode',
      color: '#ffffff',
      constraints: [
        {
          name: 'Time',
          type: 'list',
        },
        {
          name: 'Earliest Start',
          type: 'list',
        },
      ],
      duration: 580,
      end: 1656462679.447,
      endTimestamp: '2022-180T00:31:19.447',
      intent: '...',
      name: 'SetArrayTrackingMode_FIXED',
      parameters: [
        {
          name: 'Image Resolution',
          type: '',
        },
      ],
      start: 1656462099.447,
      startTimestamp: '2022-180T00:21:39.447',
      y: null,
    },
  ];
}

export function getMockPlan(planId: string): Plan {
  return {
    adaptationId: 'ops',
    endTimestamp: '1995-12-18T03:28:00',
    id: planId,
    name: `Plan ${planId}`,
    startTimestamp: '1995-12-17T03:24:00',
  };
}

export function getMockPlans(): Plan[] {
  const plans: Plan[] = [];
  for (let i = 0, len = 10; i < len; ++i) {
    plans.push(getMockPlan(`${i}`));
  }
  return plans;
}

@Injectable({
  providedIn: 'root',
})
export class PlanMockService implements PlanServiceInterface {
  createActivity(
    apiBaseUrl: string = '',
    planId: string,
    data: ActivityInstance,
  ): Observable<ActivityInstance> {
    return Observable.create((o: Observer<ActivityInstance>) => {
      const activities = getMockActivities();
      const activityId = data.activityId || Date.now().toString();
      const mockDetail = activities[activityId];
      o.next({ ...mockDetail, ...data, activityId });
    });
  }

  createPlan(apiBaseUrl: string, plan: Plan): Observable<Plan> {
    return Observable.create((o: Observer<Plan>) => {
      o.next(plan);
      o.complete();
    });
  }

  deleteActivity(
    apiBaseUrl: string,
    planId: string,
    activityId: string,
  ): Observable<{}> {
    return Observable.create((o: Observer<{}>) => {
      o.next({});
      o.complete();
    });
  }

  deletePlan(apiBaseUrl: string, planId: string): Observable<{}> {
    return Observable.create((o: Observer<{}>) => {
      o.next({});
      o.complete();
    });
  }

  getActivities(
    apiBaseUrl: string = '',
    planId: string,
  ): Observable<ActivityInstance[]> {
    return Observable.create((o: Observer<ActivityInstance[]>) => {
      o.next(getMockActivities());
      o.complete();
    });
  }

  getPlans(apiBaseUrl: string = ''): Observable<Plan[]> {
    return Observable.create((o: Observer<Plan[]>) => {
      o.next(getMockPlans());
      o.complete();
    });
  }

  updateActivity(
    apiBaseUrl: string = '',
    planId: string = '',
    activityId: string = '',
    activityInstance: ActivityInstance,
  ): Observable<null> {
    return Observable.create((o: Observer<null>) => {
      o.next(null);
      o.complete();
    });
  }
}
