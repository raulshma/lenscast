/**
 * The auth/session lifecycle as one derived verdict over the three auth
 * signals (web/src/hooks/useAppState.ts). Booting → open/locked → signedIn.
 *
 * "Demoted" (auth armed mid-session by a 401) is deliberately NOT a
 * resting variant: its resting field state (authChecked, authRequired,
 * !authenticated) is field-identical to a fresh auth-on-no-session load,
 * and no consumer distinguishes them (the login screen renders for both).
 * Demotion is the transition — signOut({ demoted: true }) in the hook —
 * not a derivable phase.
 */
export type SessionPhase = 'booting' | 'open' | 'locked' | 'signedIn'

export function sessionPhase(authChecked: boolean, authRequired: boolean, authenticated: boolean): SessionPhase {
  if (!authChecked) return 'booting'
  if (!authRequired) return 'open'
  return authenticated ? 'signedIn' : 'locked'
}
