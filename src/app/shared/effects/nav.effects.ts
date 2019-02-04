/**
 * Copyright 2018, by the California Institute of Technology. ALL RIGHTS RESERVED. United States Government Sponsorship acknowledged.
 * Any commercial use must be negotiated with the Office of Technology Transfer at the California Institute of Technology.
 * This software may be subject to U.S. export control laws and regulations.
 * By accepting this document, the user agrees to comply with all applicable U.S. export laws and regulations.
 * User has the responsibility to obtain export licenses, or other export authority as may be required
 * before exporting such information to foreign countries or providing access to foreign persons
 */

import { Injectable } from '@angular/core';
import { Actions, Effect, ofType } from '@ngrx/effects';
import { tap, switchMap } from 'rxjs/operators';

import { AnalyticsService, EventType } from '../services/analytics.service';

@Injectable()
export class NavEffects {
  constructor(
    private update$: Actions,
    private analyticsService: AnalyticsService,
  ) {}

  @Effect()
  navigate$ = this.update$.pipe(
    ofType('ROUTER_NAVIGATION'),
    tap(({ payload }: any) => {
      this.analyticsService.trackEvent(
        EventType.NavigationEvent,
        payload.event.url,
      );
    }),
    switchMap(() => []),
  );
}
