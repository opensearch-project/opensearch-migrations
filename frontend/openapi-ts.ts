import { createClient } from '@hey-api/openapi-ts';

createClient({
  input: './build/openapi.json',
  output: {
    path: 'src/client',
    format: 'prettier',
    lint: 'eslint',
    tsConfigPath: './tsconfig.json'
  },
  plugins: ['@hey-api/client-next']
});