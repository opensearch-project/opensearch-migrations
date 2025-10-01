/** @type {import('jest').Config} */
module.exports = {
    preset: "ts-jest",
    testEnvironment: "node",
    roots: ["<rootDir>/tests"],
    moduleFileExtensions: ["ts", "js", "json", "yaml", "yml", "sh"],

    transform: {
        "^.+\\.(ts|js)$": "ts-jest",              // Jest 29 syntax
        "\\.(sh|ya?ml)$": "<rootDir>/tests/rawFileTransformer.cjs",
    },

    moduleNameMapper: {
        "^@/(.*)$": "<rootDir>/src/$1",
        // NOTE: add `$` so we don’t over-match and ensure a clean capture group
        "^resources/(.*)$": "<rootDir>/resources/$1",   // <-- was src/resources
    },

    // Optional niceties:
    coveragePathIgnorePatterns: ["\\.sh$", "\\.ya?ml$"],
};
