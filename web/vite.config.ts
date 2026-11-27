import { defineConfig } from 'vite'
import solidPlugin from 'vite-plugin-solid'
import tailwindcss from '@tailwindcss/vite'

// The device's real server lives on :8080, and local mocks used to squat it
// too — point dev at a different backend (scripts/web/mock-api.cjs) without
// editing this file:
//   DEV_API_ORIGIN=http://localhost:8099 npm run dev
const devApiOrigin = process.env.DEV_API_ORIGIN ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [solidPlugin(), tailwindcss()],
  base: '/',
  build: {
    outDir: '../app/src/main/assets/webui',
    emptyOutDir: true,
  },
  server: {
    port: 3000,
    proxy: {
      '/api': devApiOrigin,
      '/stream': devApiOrigin,
      '/audio': devApiOrigin,
      '/snapshot': devApiOrigin,
      '/hls': devApiOrigin,
      '/whep': devApiOrigin,
      // Same-origin WebSocket routes (WebCodecs video, talkback): the proxy
      // must carry the upgrade handshake, not just the HTTP GET.
      '/ws': { target: devApiOrigin, ws: true },
    },
  },
})
