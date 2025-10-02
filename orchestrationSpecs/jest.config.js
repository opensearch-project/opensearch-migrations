/** @type {import('jest').Config} */
module.exports = {
  preset: "ts-jest",
  testEnvironment: "node",
  roots: ["<rootDir>/tests"],
  moduleFileExtensions: ["ts", "js", "json", "yaml"],
  transform: {
    "^.+\\.(ts|js)$": "ts-jest"
  },
  moduleNameMapper: {
    "^@/(.*)$": "<rootDir>/src/$1",
    "^resources/(.*)$": "<rootDir>/src/resources/$1"
  }
};
