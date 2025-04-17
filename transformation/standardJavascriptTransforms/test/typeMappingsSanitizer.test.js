const sanitizer = require('../src/typeMappingsSanitizer.js');

describe('TypeMappingsSanitizer', () => {
    test('null context throws', () => {
        expect(() => sanitizer.main(null)).toThrow();
    });
});