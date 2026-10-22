/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/** Everything goes through rcs-gateway (8080), which checks the token and routes to the services. */
const gateway = process.env.RCS_GATEWAY_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    // the simulator page imports the example scenarios from ../deploy/examples
    fs: { allow: ['..'] },
    proxy: {
      '/api': { target: gateway, changeOrigin: true },
      '/ws': { target: gateway.replace(/^http/, 'ws'), ws: true, changeOrigin: true },
    },
  },
  build: {
    rolldownOptions: {
      output: {
        // libraries in chunks of their own: they change far less often than the app, so browsers keep them cached
        codeSplitting: {
          groups: [
            { name: 'element-plus', test: /node_modules[\\/](element-plus|@element-plus)/, priority: 3 },
            { name: 'konva', test: /node_modules[\\/](konva|vue-konva)/, priority: 2 },
            { name: 'vendor', test: /node_modules/, priority: 1 },
          ],
        },
      },
    },
    // Element Plus is registered as a whole (main.ts): its chunk alone is about 1 MB (320 kB gzipped)
    chunkSizeWarningLimit: 1024,
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.ts'],
  },
})
