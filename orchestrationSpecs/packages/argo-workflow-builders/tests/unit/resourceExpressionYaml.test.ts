import { parse as parseYaml } from "yaml";
import {
    BaseExpression,
    expr,
    FromParameterExpression,
    makeDirectTypeProxy,
    makeStringTypeProxy,
    Serialized,
    toArgoExpressionString,
    transformExpressionsDeep,
    unwrapPlaceholdersAndStringify,
} from "../../src";

describe("resource expression YAML rendering", () => {
    it("keeps dynamic list expressions unquoted when defaulted static entries are concatenated", () => {
        const manifest = {
            spec: {
                template: {
                    spec: {
                        volumes: makeDirectTypeProxy(expr.concatArrays(
                            expr.templateValue([{
                                name: "log4j-configuration",
                                configMap: {
                                    name: makeStringTypeProxy(expr.defaultTo(
                                        expr.literal("default-log4j-config"),
                                        expr.literal("")
                                    )),
                                    optional: true,
                                },
                            }]),
                            expr.templateValue([])
                        )),
                    },
                },
            },
        };

        const rendered = unwrapPlaceholdersAndStringify(transformExpressionsDeep(manifest));
        expect(rendered).toContain("volumes: {{=sprig.concat(");
        expect(rendered).toContain("sprig.default('default-log4j-config', '')");
        expect(rendered).not.toContain('volumes: "{{=sprig.concat(');
    });

    describe("automatic string-scalar escaping in manifests (#3108)", () => {
        const param = new FromParameterExpression<string, any>({ kind: "input", parameterName: "x" });

        function renderManifest(obj: any): string {
            // `true` = manifest mode (the resource renderer's setting)
            return unwrapPlaceholdersAndStringify(transformExpressionsDeep(obj, true));
        }

        it("escapes a top-level string scalar without an explicit yamlSafeString call", () => {
            const rendered = renderManifest({ data: { pem: makeStringTypeProxy(param) } });
            expect(rendered).toContain("pem: {{=toJSON(inputs.parameters.x)}}");
        });

        it("escapes a ':'-containing string scalar unquoted (ternary)", () => {
            const t = expr.ternary(expr.equals(param, expr.literal("a")), expr.literal("y:1"), expr.literal("n:2"));
            const rendered = renderManifest({ data: { picked: makeStringTypeProxy(t) } });
            expect(rendered).toContain("picked: {{=toJSON(");
            expect(rendered).not.toContain('picked: "{{=toJSON(');
        });

        it("emits an unquoted expression even when its text contains a ': ' YAML indicator", () => {
            // The rendered expr-lang for a ternary contains ' ? ' and ' : ' (with spaces) — the exact
            // case the YAML emitter force-quotes a normal scalar. The RawYaml tag emits it unquoted
            // regardless (the value is an Argo template that Argo text-substitutes before kubectl
            // parses the YAML, so it must not be wrapped in quotes).
            const t = expr.ternary(expr.equals(param, expr.literal("a")), expr.literal("yes"), expr.literal("no"));
            const rendered = renderManifest({ data: { picked: makeStringTypeProxy(t) } });
            expect(rendered).toMatch(/picked: \{\{=toJSON\(\(\(.* \? .* : .*\)\)\)\}\}/);
            expect(rendered).not.toContain('picked: "');
        });

        it("does NOT double-encode values composed inside a makeDirectTypeProxy expression", () => {
            // A volume list built via concatArrays(templateValue([...])) is one expression; the
            // inner name must NOT be individually toJSON'd (the outer block serializes it once).
            const rendered = renderManifest({
                volumes: makeDirectTypeProxy(expr.concatArrays(
                    expr.templateValue([{ name: "cfg", configMap: { name: makeStringTypeProxy(param) } }]),
                    expr.templateValue([])
                )),
            });
            expect(rendered).toContain('sprig.dict("name", inputs.parameters.x)');
            expect(rendered).not.toContain("toJSON(inputs.parameters.x)");
        });

        it("leaves non-string (makeDirectTypeProxy) scalars unwrapped", () => {
            const num: BaseExpression<Serialized<number>> =
                new FromParameterExpression<number, { kind: "input"; parameterName: string }>(
                    { kind: "input", parameterName: "replicas" });
            const rendered = renderManifest({ spec: { replicas: makeDirectTypeProxy(num) } });
            expect(rendered).toContain("replicas: {{inputs.parameters.replicas}}");
            expect(rendered).not.toContain("toJSON");
        });
    });

    describe("yamlSafeString (#3108)", () => {
        const param = new FromParameterExpression<string, any>({ kind: "input", parameterName: "pem" });

        function renderEnvValue(value: any): string {
            const manifest = { env: [{ name: "PEM", value }] };
            return unwrapPlaceholdersAndStringify(transformExpressionsDeep(manifest));
        }

        it("renders an unquoted {{=toJSON(...)}} for a parameter expression", () => {
            const rendered = renderEnvValue(expr.yamlSafeString(param));
            expect(rendered).toContain("value: {{=toJSON(inputs.parameters.pem)}}");
            // Must be unquoted — toJSON supplies its own quotes/escapes at runtime.
            expect(rendered).not.toContain('value: "{{=toJSON');
            // Must NOT collapse to a bare parameter substitution (which would drop the escaping).
            expect(rendered).not.toContain("value: {{inputs.parameters.pem}}");
            expect(rendered).not.toContain('value: "{{inputs.parameters.pem}}"');
        });

        it("emits valid escaped expr-lang source for a string literal too", () => {
            // A literal with a quote and backslash must be escaped inside toJSON's argument,
            // not pasted verbatim, so the expr-lang engine doesn't raise "invalid char escape".
            const rendered = renderEnvValue(expr.yamlSafeString("a'b\\c"));
            expect(rendered).toContain("value: {{=toJSON('a\\'b\\\\c')}}");
        });

        it("round-trips a PEM through Argo toJSON + YAML parse back to the exact original", () => {
            // Simulate the runtime path: Argo evaluates toJSON(pem) (== JSON.stringify) and
            // substitutes the result into the manifest, then kubectl's YAML parser reads it.
            const PEM = [
                "-----BEGIN CERTIFICATE-----",
                'MIIBkz/+"x\\y',
                "-----END CERTIFICATE-----",
                "",
            ].join("\n");

            const argoEvaluated = JSON.stringify(PEM); // what {{=toJSON(pem)}} produces
            const manifestLine = `value: ${argoEvaluated}`;
            const parsed = parseYaml(manifestLine) as { value: string };

            expect(parsed.value).toBe(PEM);
            expect(parsed.value).toContain("-----BEGIN CERTIFICATE-----");
            expect(parsed.value).toContain("-----END CERTIFICATE-----");
        });
    });

    describe("govaluate string literal escaping", () => {
        it("escapes backslashes in a regex literal so expr-lang source is valid", () => {
            // A regex literal like \. must render as \\. inside the single-quoted expr-lang
            // string, otherwise Argo raises "invalid char escape" at runtime.
            const rendered = toArgoExpressionString(
                expr.regexMatch(expr.literal("^a\\.[0-9]+$"), expr.literal("a.5"))
            );
            expect(rendered).toContain("sprig.regexMatch('^a\\\\.[0-9]+$', 'a.5')");
            expect(rendered).not.toContain("'^a\\.[0-9]+$'"); // not the unescaped (broken) form
        });

        it("escapes embedded single quotes in a literal", () => {
            const rendered = toArgoExpressionString(expr.regexFind(expr.literal("o'brien"), expr.literal("x")));
            expect(rendered).toContain("\\'brien");
        });

        it("emits sprig.-prefixed regex helpers (bare names are 'unknown name' at runtime)", () => {
            expect(toArgoExpressionString(expr.regexFind(expr.literal("[0-9]"), expr.literal("a1"))))
                .toContain("sprig.regexFind(");
            // regexReplaceAll(pattern, replacement, text) must emit sprig order (pattern, text, replacement)
            expect(toArgoExpressionString(
                expr.regexReplaceAll(expr.literal("[0-9]"), expr.literal("X"), expr.literal("a1b2"))
            )).toContain("sprig.regexReplaceAll('[0-9]', 'a1b2', 'X')");
        });
    });
});
