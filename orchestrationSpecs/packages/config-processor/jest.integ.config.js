const base = require("./jest.config.js");

/** @type {import('jest').Config} */
module.exports = {
    ...base,
    testRegex: ["/tests/integ/.*\\.integ\\.test\\.ts$"],
    testPathIgnorePatterns: [
        "/node_modules/",
    ],
    coveragePathIgnorePatterns: [
        ...(base.coveragePathIgnorePatterns ?? []),
        "/tests/integ/",
    ],
    collectCoverage: false,
    maxWorkers: 1,
    testTimeout: 240_000,
};
