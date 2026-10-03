// Self-hosted font faces: the dashboard must render identically offline and
// on isolated LANs, so the fonts ship with the bundle instead of being
// fetched from Google Fonts at runtime. Subset-scoped imports (latin + cyrillic
// cover the dashboard's shipped locales; Chinese text falls back to the
// system CJK font either way) keep the embedded bundle small.
import '@fontsource/inter/latin-300.css'
import '@fontsource/inter/latin-400.css'
import '@fontsource/inter/latin-500.css'
import '@fontsource/inter/latin-600.css'
import '@fontsource/inter/latin-700.css'
import '@fontsource/inter/cyrillic-300.css'
import '@fontsource/inter/cyrillic-400.css'
import '@fontsource/inter/cyrillic-500.css'
import '@fontsource/inter/cyrillic-600.css'
import '@fontsource/inter/cyrillic-700.css'
import '@fontsource/jetbrains-mono/latin-400.css'
import '@fontsource/jetbrains-mono/latin-500.css'
import '@fontsource/jetbrains-mono/latin-600.css'
import '@fontsource/jetbrains-mono/cyrillic-400.css'
import '@fontsource/jetbrains-mono/cyrillic-500.css'
import '@fontsource/jetbrains-mono/cyrillic-600.css'

import { render } from 'solid-js/web'
import App from './App'

const root = document.getElementById('root')
if (root) {
  render(() => <App />, root)
}
