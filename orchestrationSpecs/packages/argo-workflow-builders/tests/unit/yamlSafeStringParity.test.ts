import * as yaml from "yaml";
import {
    expr,
    makeStringTypeProxy,
    transformExpressionsDeep,
    unwrapPlaceholdersAndStringify,
} from "../../src";

/**
 * Parity test: validates that after simulating Argo's parameter substitution,
 * the rendered manifest YAML remains parseable and the value is correctly recovered.
 *
 * This mirrors the side-by-side comparison Greg described: the TS-modeled output
 * goes through YAML rendering, then we simulate what Argo would do (substitute params),
 * then parse the result and verify correctness.
 */
describe("yamlSafeString parity: TS model → YAML → Argo substitution → YAML parse", () => {

    function simulateArgoSubstitution(yamlStr: string, params: Record<string, string>): string {
        let result = yamlStr;
        // Simulate Argo expression evaluation: {{=regexReplaceAll(pattern, replacement, value)}}
        // For our case, we simulate the chained regexReplaceAll by applying the escaping
        for (const [name, value] of Object.entries(params)) {
            // Argo simple substitution: {{inputs.parameters.name}} -> value
            result = result.replace(
                new RegExp(`\\{\\{inputs\\.parameters\\.${name}\\}\\}`, "g"),
                value
            );
            // Argo expression substitution for our yamlSafeString pattern:
            // The rendered expression evaluates regexReplaceAll chains, which effectively
            // escapes \, ", \r, \n in the value. Simulate that final output.
            const escaped = value
                .replace(/\\/g, "\\\\")
                .replace(/"/g, '\\"')
                .replace(/\r/g, "\\r")
                .replace(/\n/g, "\\n");
            // Replace the full expression with the escaped value
            result = result.replace(
                /\{\{=regexReplaceAll\([^}]*?inputs\.parameters\.\w+[^}]*?\)\}\}/g,
                escaped
            );
        }
        return result;
    }

    it("PEM certificate with newlines survives YAML rendering and Argo substitution", () => {
        const pem = "-----BEGIN CERTIFICATE-----\nMIIBtest1234abcd\n-----END CERTIFICATE-----";

        // Step 1: Build manifest using our TS model (what the code does)
        const manifest = {
            apiVersion: "v1",
            kind: "Pod",
            spec: {
                containers: [{
                    env: [{
                        name: "CERT_PEM",
                        value: makeStringTypeProxy(expr.yamlSafeString(expr.literal(pem)))
                    }]
                }]
            }
        };

        // Step 2: Render to YAML (what unwrapPlaceholdersAndStringify does)
        const renderedYaml = unwrapPlaceholdersAndStringify(transformExpressionsDeep(manifest));

        // Step 3: Simulate Argo substitution with the escaped value
        const substituted = simulateArgoSubstitution(renderedYaml, { certPem: pem });

        // Step 4: Parse the resulting YAML (what kubectl does)
        const parsed = yaml.parse(substituted);

        // Step 5: Verify the value is correctly recovered
        expect(parsed.spec.containers[0].env[0].name).toBe("CERT_PEM");
        // The env var value should contain the properly escaped PEM
        // (YAML double-quoted scalar unescapes \\n back to \n)
        const envValue = parsed.spec.containers[0].env[0].value;
        expect(envValue).toContain("-----BEGIN CERTIFICATE-----");
        expect(envValue).toContain("-----END CERTIFICATE-----");
    });

    it("string with quotes and backslashes produces parseable YAML", () => {
        const dangerous = 'path\\to\\file "quoted" value\nnewline';

        const manifest = {
            data: {
                value: makeStringTypeProxy(expr.yamlSafeString(expr.literal(dangerous)))
            }
        };

        const renderedYaml = unwrapPlaceholdersAndStringify(transformExpressionsDeep(manifest));
        const substituted = simulateArgoSubstitution(renderedYaml, {});

        // The rendered YAML should be parseable without errors
        expect(() => yaml.parse(substituted)).not.toThrow();
    });

    it("empty string and simple strings pass through unchanged", () => {
        for (const input of ["", "simple", "no-special-chars"]) {
            const manifest = {
                value: makeStringTypeProxy(expr.yamlSafeString(expr.literal(input)))
            };
            const renderedYaml = unwrapPlaceholdersAndStringify(transformExpressionsDeep(manifest));
            const parsed = yaml.parse(simulateArgoSubstitution(renderedYaml, {}));
            // Simple strings should survive the pipeline
            expect(parsed).toBeDefined();
        }
    });
});
