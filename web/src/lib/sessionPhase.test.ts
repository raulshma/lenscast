import { describe, expect, it } from 'vitest'
import { sessionPhase } from './sessionPhase'

describe('sessionPhase', () => {
  it('maps each of the 8 signal combos to exactly one variant (totality)', () => {
    const combos: Array<[boolean, boolean, boolean]> = [
      [false, false, false],
      [false, false, true],
      [false, true, false],
      [false, true, true],
      [true, false, false],
      [true, false, true],
      [true, true, false],
      [true, true, true],
    ]
    for (const [authChecked, authRequired, authenticated] of combos) {
      const phase = sessionPhase(authChecked, authRequired, authenticated)
      expect(['booting', 'open', 'locked', 'signedIn']).toContain(phase)
    }
    // Spot-pin each resting variant inside the table:
    expect(sessionPhase(false, false, false)).toBe('booting')
    expect(sessionPhase(false, true, true)).toBe('booting')
    expect(sessionPhase(true, false, false)).toBe('open')
    expect(sessionPhase(true, false, true)).toBe('open')
    expect(sessionPhase(true, true, false)).toBe('locked')
    expect(sessionPhase(true, true, true)).toBe('signedIn')
  })

  it('booting wins until the probe answers, whatever the other signals claim', () => {
    // authChecked=false short-circuits before authRequired/authenticated are
    // read: a pre-probe render must never flash open/locked/signedIn.
    expect(sessionPhase(false, false, false)).toBe('booting')
    expect(sessionPhase(false, false, true)).toBe('booting')
    expect(sessionPhase(false, true, false)).toBe('booting')
    expect(sessionPhase(false, true, true)).toBe('booting')
  })

  it('auth armed + not authenticated reads locked — the demote resting state', () => {
    // signOut({ demoted: true }) lands on (authChecked, authRequired, !authenticated),
    // field-identical to a fresh auth-on-no-session load; both render the
    // login screen, and demotion itself is the transition, not a variant.
    expect(sessionPhase(true, true, false)).toBe('locked')
  })

  it('auth off reads open even after authenticated was cleared — the auth-off logout corner', () => {
    // handleLogout on a no-auth server wipes the session fields while
    // authRequired stays false: the dashboard keeps rendering, not the login.
    expect(sessionPhase(true, false, false)).toBe('open')
  })
})
