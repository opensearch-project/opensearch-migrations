const base = require("./jest.config.js");

/** @type {import('jest').Config} */
module.exports = {
    ...base,
    testRegex: ["/tests/integ/.*\\.integ\\.test\\.ts$"],
    testPathIgnorePatterns: [
        "/node_modules/",
    ],
    transformIgnorePatterns: [
        "/node_modules/(?!(archiver|compress-commons|crc32-stream|zip-stream|is-stream|get-port)/)",
    ],
    transform: {
        "^.+\\.ts$": ["ts-jest"],
        "^.+\\.js$": ["ts-jest", { tsconfig: { allowJs: true } }],
    },
    coveragePathIgnorePatterns: [
        ...(base.coveragePathIgnorePatterns ?? []),
        "/tests/integ/",
    ],
    collectCoverage: false,
    maxWorkers: 1,
    testTimeout: 240_000,
};
