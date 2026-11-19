import { defineConfig } from 'vite';

// Die gebaute Web-App liegt in den Assets der Android-App und wird vom lokalen
// Server auf dem Fire TV ausgeliefert.
export default defineConfig({
  base: './',
  build: {
    outDir: '../app/src/main/assets/web',
    emptyOutDir: true,
    target: 'es2019',
    assetsInlineLimit: 0,
  },
  worker: { format: 'es' },
  test: { include: ['test/**/*.test.ts'], testTimeout: 300000 },
});
