import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    rollupOptions: {
      output: {
        /**
         * Split the two large, rarely-changing dependencies into their own chunks.
         *
         * <p>Recharts is roughly half the bundle and changes only when it is upgraded, so keeping
         * it separate means a change to application code no longer invalidates it in the
         * browser's cache. The translation catalogues are already separate — they are loaded on
         * demand by `I18nProvider`, so a visitor reading English never downloads the other eight.
         */
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          charts: ['recharts'],
        },
      },
    },
  },
  server: {
    port: 5173,
    // Same-origin in dev, so the browser never sends a preflight and the backend's CORS
    // allow-list is exercised in production only.
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
