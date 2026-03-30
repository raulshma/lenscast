import { Show } from 'solid-js'

interface Props {
  loginUser: () => string
  setLoginUser: (v: string) => void
  loginPass: () => string
  setLoginPass: (v: string) => void
  loginError: () => string
  loginLoading: () => boolean
  handleLogin: (e: Event) => void
}

export default function LoginScreen(props: Props) {
  return (
    <div class="login-screen">
      <div class="login-ambient" />
      <div class="login-card">
        <div class="login-logo">
          <div class="login-lens-ring">
            <div class="login-lens-inner">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                <circle cx="12" cy="13" r="4" />
              </svg>
            </div>
          </div>
          <h1 class="login-title">LensCast</h1>
          <p class="login-subtitle">Remote Camera Control</p>
        </div>

        <form onSubmit={props.handleLogin} class="login-form">
          <div class="form-field">
            <label class="field-label" for="login-username">Username</label>
            <input
              id="login-username"
              type="text"
              class="field-input"
              placeholder="Enter username"
              value={props.loginUser()}
              onInput={(e) => props.setLoginUser(e.currentTarget.value)}
              autocomplete="username"
              required
            />
          </div>
          <div class="form-field">
            <label class="field-label" for="login-password">Password</label>
            <input
              id="login-password"
              type="password"
              class="field-input"
              placeholder="Enter password"
              value={props.loginPass()}
              onInput={(e) => props.setLoginPass(e.currentTarget.value)}
              autocomplete="current-password"
              required
            />
          </div>

          <Show when={props.loginError()}>
            <div class="login-error">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span>{props.loginError()}</span>
            </div>
          </Show>

          <button
            id="login-submit"
            class="login-btn"
            type="submit"
            disabled={props.loginLoading()}
          >
            <Show when={props.loginLoading()} fallback="Sign In">
              <span class="login-spinner" />
              Signing in...
            </Show>
          </button>
        </form>
      </div>
    </div>
  )
}
