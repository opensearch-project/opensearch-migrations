/// <reference types="vitest" />
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { copyFileSync, mkdirSync, existsSync } from 'node:fs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

/**
 * Custom Vite plugin to copy the generated schema file to the dist folder.
 * This ensures the schema is available at ./workflow-schema.json in production.
 */
function copySchemaPlugin() {
  return {
    name: 'copy-schema',
    writeBundle() {
      const srcPath = resolve(__dirname, 'generated/schemas/workflow-schema.json');
      const destDir = resolve(__dirname, 'dist');
      const destPath = resolve(destDir, 'workflow-schema.json');
      
      if (existsSync(srcPath)) {
        if (!existsSync(destDir)) {
          mkdirSync(destDir, { recursive: true });
        }
        copyFileSync(srcPath, destPath);
        console.log('✓ Copied workflow-schema.json to dist/');
      } else {
        console.warn('⚠ Warning: workflow-schema.json not found at', srcPath);
      }
    },
  };
}

export default defineConfig({
  plugins: [react(), copySchemaPlugin()],
  base: './',
  root: '.',
  resolve: {
    alias: {
      '@': resolve(__dirname, './src'),
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      input: resolve(__dirname, 'index.html'),
    },
  },
  publicDir: 'public',
  server: {
    port: 3000,
    open: true,
    fs: {
      allow: ['.'],
      deny: ['cloudscape-docs', 'react-docs'],
    },
  },
  optimizeDeps: {
    include: ['react', 'react-dom', 'zod', 'yaml', 'ace-builds'],
    exclude: [],
    entries: ['src/main.tsx'],
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.{test,spec}.{ts,tsx}', 'src/test/**/*'],
    },
  },
});
