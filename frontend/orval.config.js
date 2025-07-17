module.exports = {
    'backend': {
      output: {
        mode: 'single',
        target: './src/lib/orval.ts',
        schemas: './src/lib/orval/models',
        mock: true,
      },
      input: {
        target: './build/openapi.json',
      },
    },
  };