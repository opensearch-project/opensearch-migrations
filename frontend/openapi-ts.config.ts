import { defineConfig } from '@hey-api/openapi-ts';

export default defineConfig({
  input: './build/openapi/openapi.json',
  output: {
    path: 'src/generated/api',
    format: 'prettier',
    lint: 'eslint',
    tsConfigPath: './tsconfig.json'
  },
  plugins: ['@hey-api/client-next']
});