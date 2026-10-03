import {expectTypeOf} from "expect-type";
import {
    configMapKey,
    EnvironmentVariableValueSource,
    ExpressionOrConfigMapValue,
    renderWorkflowTemplate,
    typeToken,
    WorkflowBuilder
} from "../../src";

const EXAMPLE_RESOURCES = {
    requests: {cpu: "100m", memory: "128Mi"},
    limits: {cpu: "200m", memory: "256Mi"}
};

describe("environment variable value types", () => {
    it("models literal values and valueFrom sources as an exclusive union", () => {
        const literal: ExpressionOrConfigMapValue<string> = "plain";
        const configMapValue: ExpressionOrConfigMapValue<string> = {
            configMapKeyRef: configMapKey("settings", "log-level"),
            type: typeToken<string>()
        };
        const secretValue: ExpressionOrConfigMapValue<string> = {
            secretKeyRef: configMapKey("credentials", "password", true),
            type: typeToken<string>()
        };

        expectTypeOf(configMapValue)
            .toMatchTypeOf<EnvironmentVariableValueSource<string>>();
        expectTypeOf(secretValue)
            .toMatchTypeOf<EnvironmentVariableValueSource<string>>();

        // @ts-expect-error - valueFrom sources require a type token
        const missingType: ExpressionOrConfigMapValue<string> = {
            secretKeyRef: configMapKey("credentials", "password")
        };

        // @ts-expect-error - an environment value cannot use both source kinds
        const ambiguousSource: ExpressionOrConfigMapValue<string> = {
            configMapKeyRef: configMapKey("settings", "log-level"),
            secretKeyRef: configMapKey("credentials", "password"),
            type: typeToken<string>()
        };

        const legacyFrom: ExpressionOrConfigMapValue<string> = {
            // @ts-expect-error - the renderer consumes Kubernetes valueFrom fields, not parameter-style from
            from: configMapKey("settings", "log-level"),
            type: typeToken<string>()
        };

        expect(literal).toBe("plain");
        expect(missingType).toBeDefined();
        expect(ambiguousSource).toBeDefined();
        expect(legacyFrom).toBeDefined();
    });

    it("enforces the value union at addEnvVar", () => {
        WorkflowBuilder.create({k8sResourceName: "typed-env-values"})
            .addTemplate("main", t => t
                .addContainer(c => {
                    const base = c
                        .addImageInfo("alpine", "IfNotPresent")
                        .addResources(EXAMPLE_RESOURCES);

                    // @ts-expect-error - missing type token must be rejected by the builder API
                    base.addEnvVar("INVALID", {
                        secretKeyRef: configMapKey("credentials", "password")
                    });

                    return base
                        .addEnvVar("LITERAL", "plain")
                        .addEnvVar("CONFIG_MAP", {
                            configMapKeyRef: configMapKey("settings", "log-level"),
                            type: typeToken<string>()
                        })
                        .addEnvVar("SECRET", {
                            secretKeyRef: configMapKey("credentials", "password", true),
                            type: typeToken<string>()
                        });
                })
            );
    });

    it("renders literal values as value and typed sources as valueFrom", () => {
        const workflow = WorkflowBuilder.create({k8sResourceName: "render-env-values"})
            .addTemplate("main", t => t
                .addContainer(c => c
                    .addImageInfo("alpine", "IfNotPresent")
                    .addResources(EXAMPLE_RESOURCES)
                    .addEnvVar("LITERAL", "plain")
                    .addEnvVar("CONFIG_MAP", {
                        configMapKeyRef: configMapKey("settings", "log-level", true),
                        type: typeToken<string>()
                    })
                    .addEnvVar("SECRET", {
                        secretKeyRef: configMapKey("credentials", "password", true),
                        type: typeToken<string>()
                    })
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(workflow);
        const template = rendered.spec.templates.find(t => t.name === "main");

        expect(template?.container?.env).toEqual([
            {name: "LITERAL", value: "plain"},
            {
                name: "CONFIG_MAP",
                valueFrom: {
                    configMapKeyRef: {
                        name: "settings",
                        key: "log-level",
                        optional: true
                    }
                }
            },
            {
                name: "SECRET",
                valueFrom: {
                    secretKeyRef: {
                        name: "credentials",
                        key: "password",
                        optional: true
                    }
                }
            }
        ]);
    });
});
