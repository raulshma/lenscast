import { defineConfig } from 'vitest/config'
import solid from 'vite-plugin-solid'

// Pure-logic tests run in the node env; component (*.test.tsx) smoke tests
// opt into jsdom via a per-file `// @vitest-environment jsdom` docblock.
// `hot: false` keeps the plugin from injecting its HMR runtime (solid-refresh),
// which vitest's module loader cannot resolve.
export default defineConfig({
  plugins: [solid({ hot: false })],
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
})
