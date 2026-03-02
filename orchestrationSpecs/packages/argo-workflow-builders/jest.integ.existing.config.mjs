// Jest config for running integ tests against an existing cluster.
// Usage: npm run test:integ:existing
// Set KUBECONFIG, INTEG_TEST_NAMESPACE, INTEG_ARGO_NAMESPACE as needed.
// Default Argo namespace is "ma" (matches minikube localTesting.sh setup).

export default {
  preset: "ts-jest/presets/default-esm",
  testEnvironment: "node",
  extensionsToTreatAsEsm: [".ts"],
  roots: ["<rootDir>/tests/integ"],
  testMatch: ["**/*.integ.test.ts", "**/*.parity.test.ts"],
  testTimeout: 120_000,
  maxWorkers: 1,
  globalSetup: "<rootDir>/tests/integ/infra/setupExisting.ts",
  globalTeardown: "<rootDir>/tests/integ/infra/teardownExisting.ts",
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
        noImplicitAny: false,
      },
    }],
  },
  reporters: [
    "default",
    "<rootDir>/tests/integ/infra/parityReporter.mjs",
  ],
};
