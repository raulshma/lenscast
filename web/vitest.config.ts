import { defineConfig } from 'vitest/config'

// Pure-logic tests only (contract fixtures, defaults) — no DOM needed.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
