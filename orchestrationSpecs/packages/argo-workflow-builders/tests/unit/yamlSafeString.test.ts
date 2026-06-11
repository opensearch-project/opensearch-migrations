import {
    expr,
    makeStringTypeProxy,
    transformExpressionsDeep,
    unwrapPlaceholdersAndStringify,
} from "../../src";

describe("yamlSafeString escapes values for safe YAML manifest embedding", () => {
    it("chains regexReplaceAll calls to escape backslashes, quotes, CR, and newlines", () => {
        const value = expr.literal("line1\nline2");
        const manifest = {
            value: makeStringTypeProxy(expr.yamlSafeString(value))
        };

        const rendered = unwrapPlaceholdersAndStringify(transformExpressionsDeep(manifest));
        // Should chain multiple regexReplaceAll calls
        const regexCount = (rendered.match(/regexReplaceAll/g) || []).length;
        expect(regexCount).toBe(4); // backslash, quote, \r, \n
    });

    it("escapes newlines to prevent YAML document separator issues", () => {
        const pemValue = expr.literal("-----BEGIN CERTIFICATE-----\nMIIBtest\n-----END CERTIFICATE-----");
        const manifest = {
            env: [{
                name: "CERT_PEM",
                value: makeStringTypeProxy(expr.yamlSafeString(pemValue))
            }]
        };

        const rendered = unwrapPlaceholdersAndStringify(transformExpressionsDeep(manifest));
        expect(rendered).toContain("regexReplaceAll");
        // Newline escape pattern present
        expect(rendered).toContain("\\\\n");
    });

    it("escapes in correct order: backslashes first, then quotes, then control chars", () => {
        const value = expr.literal("test");
        const manifest = {
            value: makeStringTypeProxy(expr.yamlSafeString(value))
        };

        const rendered = unwrapPlaceholdersAndStringify(transformExpressionsDeep(manifest));
        // The outermost regexReplaceAll should be for \n (last applied = outermost)
        // and the innermost should be for backslash (first applied = innermost)
        const newlinePos = rendered.lastIndexOf("\\\\n");
        const backslashPos = rendered.indexOf("\\\\\\\\");
        expect(backslashPos).toBeLessThan(newlinePos);
    });
});
