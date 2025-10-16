/** @type {import('jest').Config} */
module.exports = {
    preset: "ts-jest",
    testEnvironment: "node",
    roots: ["<rootDir>/tests"],
    moduleFileExtensions: ["ts", "js", "json", "yaml", "yml", "sh"],

    transform: {
        "^.+\\.(ts|js)$": "ts-jest"
    },

    moduleNameMapper: {
        // NOTE: add `$` so we don’t over-match and ensure a clean capture group
        "^resources/(.*)$": "<rootDir>/resources/$1",   // <-- was src/resources
    },

    // Optional niceties:
    coveragePathIgnorePatterns: ["\\.sh$", "\\.ya?ml$"],
};
