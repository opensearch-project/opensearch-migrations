import {
    expr,
    makeDirectTypeProxy,
    makeStringTypeProxy,
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
});
