import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:9091',
      '/q': 'http://localhost:9091',
    },
  },
  build: {
    outDir: '../fiq-server/src/main/resources/META-INF/resources',
    emptyOutDir: true,
  },
})

