const main = require("../src/typeMappingsSanitizer");

describe('TypeMappingsSanitizer', () => {
    test('null context throws', () => {
        expect(() => main(null)).toThrow();
    });
});