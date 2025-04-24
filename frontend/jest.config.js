const nextJest = require('next/jest');

const createJestConfig = nextJest({ dir: './'});

module.exports = createJestConfig({
  testEnvironment: 'jsdom',
  setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
  testPathIgnorePatterns: ['<rootDir>/.next/', '<rootDir>/node_modules/', '<rootDir>/__tests__/__utils__/'],
});
