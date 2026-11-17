import { Injectable, inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { MatchService } from '../services/match.service';

@Injectable({
  providedIn: 'root'
})
export class AuthGuard {
  private readonly matchService = inject(MatchService);
  private readonly router = inject(Router);

  canActivate(): Observable<boolean | UrlTree> {
    const cachedUser = this.matchService.authUser();
    if (cachedUser?.authenticated && cachedUser.email?.toLowerCase().endsWith('@voyager-inc.com')) {
      return of(true);
    }

    return this.matchService.getCurrentUser().pipe(
      map((user) => {
        if (user && user.authenticated && user.email?.toLowerCase().endsWith('@voyager-inc.com')) {
          return true;
        }
        return this.router.createUrlTree(['/login']);
      }),
      catchError(() => of(this.router.createUrlTree(['/login'])))
    );
  }
}

export const authGuard: CanActivateFn = () => {
  return inject(AuthGuard).canActivate();
};
