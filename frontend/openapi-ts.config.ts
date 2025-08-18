import { defineConfig } from '@hey-api/openapi-ts';

export default defineConfig({
  input: './build/openapi/openapi.json',
  output: {
    path: 'src/generated/api',
    format: 'prettier',
    lint: 'eslint',
    tsConfigPath: './tsconfig.json'
  },
  plugins: [
    {
      name: '@hey-api/transformers',
      dates: true,
    },
    {
      name: '@hey-api/sdk',
      transformer: true,
    },
    '@hey-api/client-next',
  ]
});