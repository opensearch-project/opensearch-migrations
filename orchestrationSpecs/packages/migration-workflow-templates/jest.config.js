const ignoreDep = "/node_modules/@opensearch-migrations/argo-workflow-builders/";

/** @type {import('jest').Config} */
module.exports = {
    rootDir: __dirname,
    testEnvironment: "node",

    // keep tests scoped to this package
    roots: ["<rootDir>/tests"],
    // testMatch: ["<rootDir>/tests/**/*.test.ts"],
    testRegex: ["^" + require("path").join(__dirname, "tests") + "/.*\\.test\\.ts$"],

    // only transform TS, not JS
    transform: {
        '^.+\\.ts$': ['ts-jest'],
        "\\.(sh|ya?ml)$": "<rootDir>/tests/rawFileTransformer.cjs"
    },

    // ignore workspace deps (absolute-path friendly)
    testPathIgnorePatterns: [
        "/node_modules/",
        ignoreDep
    ],
    modulePathIgnorePatterns: [
        ignoreDep + "(tests|dist/tests)/"
    ],
    transformIgnorePatterns: [
        "/node_modules/",
        ignoreDep
    ],

    moduleNameMapper: {
        "^@/(.*)$": "<rootDir>/src/$1",
        "^resources/(.*)$": "<rootDir>/resources/$1"
    },

    moduleFileExtensions: ["ts", "js", "json", "yaml", "yml", "sh"],
    coveragePathIgnorePatterns: ["\\.sh$", "\\.ya?ml$"]
};
