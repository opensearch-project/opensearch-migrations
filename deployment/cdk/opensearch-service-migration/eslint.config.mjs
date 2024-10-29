import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
    eslint.configs.recommended,
    ...tseslint.configs.strict,
    ...tseslint.configs.stylistic,
    {
        ignores: ['**/*.js', 'dist/**/*.d.ts'],
    },
    {
        languageOptions: {
            globals: {
                jest: true
            }
        }
    }
);
