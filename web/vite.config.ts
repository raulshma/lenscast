import { defineConfig } from 'vite'
import solidPlugin from 'vite-plugin-solid'
import tailwindcss from '@tailwindcss/vite'

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
      '/api': 'http://localhost:8080',
      '/stream': 'http://localhost:8080',
      '/snapshot': 'http://localhost:8080',
    },
  },
})
