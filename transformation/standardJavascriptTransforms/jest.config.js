/**
 * Jest configuration file
 */
module.exports = {
    // Enable ES Modules support
    transform: {},
    // extensionsToTreatAsEsm: ['.js'],
    // Use Node.js as the test environment
    testEnvironment: "node",

    // Directory for coverage reports
    coverageDirectory: "coverage",

    // Files to include in coverage calculations
    collectCoverageFrom: [
        "src/**/*.js"
    ],

    // Coverage report formats
    coverageReporters: [
        "lcov",
        "text",
        "json-summary"
    ],

    // Test reporters
    reporters: [
        "default",
        ["jest-junit", {
            "outputDirectory": "./build/test-results/test",
            "outputName": "js-test-results.xml"
        }]
    ],

    // Automatically clear mock calls and instances between every test
    clearMocks: true,

    // A list of paths to directories that Jest should use to search for files in
    roots: [
        "<rootDir>/src",
        "<rootDir>/test"
    ],

    // Test file pattern
    testMatch: [
        "**/test/**/*.test.js"
    ]
};