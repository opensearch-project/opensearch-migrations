module.exports = {
    preset: 'ts-jest',
    testEnvironment: 'node',
    // adjust if your test dir is different
    testMatch: ['**/tests/**/*.test.ts'],
    // optional, but can help with path mapping
    moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],
};
