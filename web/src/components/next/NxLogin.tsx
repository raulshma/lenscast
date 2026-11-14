import { Show } from 'solid-js'
import { t } from '../../lib/i18n'
import { Button, TextInput, Field } from './ui'

interface Props {
  loginUser: () => string
  setLoginUser: (v: string) => void
  loginPass: () => string
  setLoginPass: (v: string) => void
  loginError: () => string
  loginLoading: () => boolean
  handleLogin: (e: Event) => void
}

/**
 * The console's sign-in: the same props and keys as the classic
 * LoginScreen, as one centered plate on the body-black canvas. The
 * single brand moment is the logo's lens ring — oversized, dim, drawn
 * behind the plate in CSS (layout.css). Golos labels, hairline inputs,
 * one cyan submit, the error in red text below. Rendered by the
 * dispatcher while data-ui='next'.
 */
export default function NxLogin(props: Props) {
  return (
    <div class="nx-login">
      <div class="nx-login-ring" aria-hidden="true" />
      <div class="nx-login-card">
        <div class="nx-login-brand">
          <img src="/logo.svg" alt="LensCast" width="40" height="40" />
          <span class="nx-login-wordmark">LensCast</span>
          <span class="nx-login-subtitle">{t('login.subtitle')}</span>
        </div>

        <form onSubmit={props.handleLogin} class="nx-login-form">
          <Field label={t('login.username')}>
            <TextInput
              value={props.loginUser()}
              onInput={props.setLoginUser}
              placeholder={t('login.usernamePlaceholder')}
              ariaLabel={t('login.username')}
            />
          </Field>
          <Field label={t('login.password')}>
            <TextInput
              value={props.loginPass()}
              onInput={props.setLoginPass}
              type="password"
              placeholder={t('login.passwordPlaceholder')}
              ariaLabel={t('login.password')}
            />
          </Field>

          <Show when={props.loginError()}>
            <div class="nx-banner nx-banner-danger" role="alert">{props.loginError()}</div>
          </Show>

          <Button
            type="submit"
            variant="primary"
            size="lg"
            loading={props.loginLoading()}
            ariaLabel={t('login.signIn')}
          >
            <Show when={props.loginLoading()} fallback={t('login.signIn')}>
              {t('login.signingIn')}
            </Show>
          </Button>
        </form>
      </div>
    </div>
  )
}
