const nextJest = require('next/jest');

const createJestConfig = nextJest({ dir: './'});

module.exports = createJestConfig({
  testEnvironment: 'jest-fixed-jsdom',
  setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
  testPathIgnorePatterns: ['<rootDir>/.next/', '<rootDir>/node_modules/', '<rootDir>/__tests__/__utils__/'],
  moduleNameMapper: {
    "^@tests/(.*)$": "<rootDir>/__tests__/$1",
    "^@/(.*)$": "<rootDir>/src/$1"
  },
  reporters: [
    "default",
    [
      "jest-stare",
      {
        "resultDir": "build/test-results/jest",
        "reportTitle": "jest-test-results"
      }
    ]
  ]
});
