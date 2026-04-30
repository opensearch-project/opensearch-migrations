/** @type {import('jest').Config} */
module.exports = {
    rootDir: __dirname,
    testEnvironment: "node",
    roots: ["<rootDir>/tests"],
    testRegex: ["^" + require("path").join(__dirname, "tests") + "/.*\\.test\\.ts$"],
    transform: {
        '^.+\\.ts$': ['ts-jest']
    },
    testPathIgnorePatterns: ["/node_modules/"],
    moduleNameMapper: {
        "@opensearch-migrations/schemas": "<rootDir>/../schemas/src",
        "@opensearch-migrations/config-processor": "<rootDir>/../config-processor/src"
    },
    setupFiles: [
        "<rootDir>/tests/setupUnifiedSchemaPath.ts"
    ],
    moduleFileExtensions: ["ts", "js", "json", "yaml", "yml"]
};
