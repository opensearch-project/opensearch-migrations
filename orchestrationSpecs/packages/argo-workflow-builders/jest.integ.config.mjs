export default {
  preset: "ts-jest/presets/default-esm",
  testEnvironment: "node",
  extensionsToTreatAsEsm: [".ts"],
  roots: ["<rootDir>/tests/integ"],
  testMatch: ["**/*.integ.test.ts"],
  testTimeout: 120_000,
  maxWorkers: 1,
  globalSetup: "<rootDir>/tests/integ/infra/setup.ts",
  globalTeardown: "<rootDir>/tests/integ/infra/teardown.ts",
  moduleFileExtensions: ["ts", "js", "json"],
  moduleNameMapper: {
    "^(\\.{1,2}/.*)\\.js$": "$1",
  },
  transform: {
    "^.+\\.ts$": ["ts-jest", {
      useESM: true,
      tsconfig: {
        module: "ES2022",
        moduleResolution: "bundler",
      },
    }],
  },
};
