/** @type {import('jest').Config} */
module.exports = {
    rootDir: __dirname,
    testEnvironment: "node",
    roots: ["<rootDir>/tests"],
    testRegex: ["^" + require("path").join(__dirname, "tests") + "/.*\\.test\\.ts$"],
    transform: {
        "^.+\\.ts$": ["ts-jest"],
    },
    moduleNameMapper: {
        "@opensearch-migrations/schemas/browser": "<rootDir>/../schemas/src/browser.ts",
    },
    moduleFileExtensions: ["ts", "js", "json"],
};
