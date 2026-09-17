import {expectTypeOf} from "expect-type";
import {
    ContainerBuilder,
    ExpressionOrConfigMapValue,
    OutputArtifactDef,
    renderWorkflowTemplate,
    WorkflowBuilder
} from "../../src";

type ScopesOf<Builder> =
    Builder extends ContainerBuilder<
        any,
        any,
        infer ContainerScope,
        infer VolumeScope,
        infer EnvScope,
        infer OutputParamsScope,
        infer PodConfigBrands,
        infer ArtifactScope
    >
        ? {
            container: ContainerScope;
            volumes: VolumeScope;
            env: EnvScope;
            outputs: OutputParamsScope;
            podConfig: PodConfigBrands;
            artifacts: ArtifactScope;
        }
        : never;

type ArtifactScopeOf<Builder> =
    ScopesOf<Builder>["artifacts"];

const EXAMPLE_RESOURCES = {
    requests: {cpu: "100m", memory: "128Mi"},
    limits: {cpu: "200m", memory: "256Mi"}
};

const SCRATCH_VOLUME = {
    emptyDir: {},
    mountPath: "/scratch",
    readOnly: true
};

describe("ContainerBuilder scope preservation", () => {
    it("preserves every non-environment generic scope and replaces only EnvScope", () => {
        WorkflowBuilder.create({k8sResourceName: "generic-scope-preservation"})
            .addTemplate("main", t => t
                .addContainer(c => {
                    const beforeEnv = c
                        .addImageInfo("alpine", "IfNotPresent")
                        .addCommand(["sh", "-c"])
                        .addResources(EXAMPLE_RESOURCES)
                        .addVolumesFromRecord({scratch: SCRATCH_VOLUME})
                        .addArtifactOutput("result", "/tmp/result.txt")
                        .addPodMetadata(() => ({labels: {first: "value"}}));

                    const withEnv = beforeEnv.addEnvVars(emptyEnv => {
                        expectTypeOf<keyof ScopesOf<typeof emptyEnv>["env"]>()
                            .toEqualTypeOf<never>();

                        return emptyEnv
                            .addEnvVar("FIRST", "one")
                            .addEnvVar("SECOND", "two");
                    });

                    type Before = ScopesOf<typeof beforeEnv>;
                    type After = ScopesOf<typeof withEnv>;

                    expectTypeOf<After["container"]>().toEqualTypeOf<Before["container"]>();
                    expectTypeOf<After["volumes"]>().toEqualTypeOf<Before["volumes"]>();
                    expectTypeOf<After["outputs"]>().toEqualTypeOf<Before["outputs"]>();
                    expectTypeOf<After["podConfig"]>().toEqualTypeOf<Before["podConfig"]>();
                    expectTypeOf<After["artifacts"]>().toEqualTypeOf<Before["artifacts"]>();
                    expectTypeOf<keyof After["env"]>().toEqualTypeOf<"FIRST" | "SECOND">();
                    expectTypeOf<After["env"]["FIRST"]>()
                        .toEqualTypeOf<ExpressionOrConfigMapValue<string>>();
                    expectTypeOf<After["env"]["SECOND"]>()
                        .toEqualTypeOf<ExpressionOrConfigMapValue<string>>();

                    return withEnv;
                })
            );
    });

    it("preserves volumes and artifact outputs while building environment variables", () => {
        const workflow = WorkflowBuilder.create({k8sResourceName: "scope-preservation"})
            .addTemplate("main", t => t
                .addContainer(c => c
                    .addImageInfo("alpine", "IfNotPresent")
                    .addCommand(["sh", "-c"])
                    .addResources(EXAMPLE_RESOURCES)
                    .addVolumesFromRecord({scratch: SCRATCH_VOLUME})
                    .addArtifactOutput("result", "/tmp/result.txt")
                    .addEnvVars(env => env
                        .addEnvVar("FIRST", "one")
                        .addEnvVar("SECOND", "two")
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(workflow);
        const template = rendered.spec.templates.find(t => t.name === "main");

        expect(template?.volumes).toContainEqual({
            name: "scratch",
            emptyDir: {}
        });
        expect(template?.container?.volumeMounts).toContainEqual({
            name: "scratch",
            mountPath: "/scratch",
            readOnly: true
        });
        expect(template?.container?.env).toEqual([
            {name: "FIRST", value: "one"},
            {name: "SECOND", value: "two"}
        ]);
        expect(template?.outputs?.artifacts).toContainEqual({
            name: "result",
            path: "/tmp/result.txt",
            archive: {none: {}}
        });
    });

    it("preserves volume names for duplicate checks after addEnvVars", () => {
        WorkflowBuilder.create({k8sResourceName: "volume-scope-preservation"})
            .addTemplate("main", t => t
                .addContainer(c => {
                    const withEnv = c
                        .addImageInfo("alpine", "IfNotPresent")
                        .addResources(EXAMPLE_RESOURCES)
                        .addVolumesFromRecord({scratch: SCRATCH_VOLUME})
                        .addEnvVars(env => env.addEnvVar("FIRST", "one"));

                    // @ts-expect-error - addEnvVars must preserve existing volume names
                    return withEnv.addVolumesFromRecord({scratch: SCRATCH_VOLUME});
                })
            );

        expect(true).toBe(true);
    });

    it("preserves the exact artifact scope after addEnvVars", () => {
        WorkflowBuilder.create({k8sResourceName: "artifact-scope-preservation"})
            .addTemplate("main", t => t
                .addContainer(c => {
                    const withEnv = c
                        .addImageInfo("alpine", "IfNotPresent")
                        .addResources(EXAMPLE_RESOURCES)
                        .addArtifactOutput("result", "/tmp/result.txt")
                        .addEnvVars(env => env.addEnvVar("FIRST", "one"));

                    expectTypeOf<ArtifactScopeOf<typeof withEnv>>()
                        .toMatchTypeOf<{result: OutputArtifactDef}>();

                    // @ts-expect-error - addEnvVars must preserve the exact artifact-name scope
                    const invalidArtifactName: keyof ArtifactScopeOf<typeof withEnv> = "missing";
                    expect(invalidArtifactName).toBe("missing");

                    return withEnv;
                })
            );
    });

    it("preserves pod configuration brands after addEnvVars", () => {
        WorkflowBuilder.create({k8sResourceName: "pod-config-scope-preservation"})
            .addTemplate("main", t => t
                .addContainer(c => {
                    const withEnv = c
                        .addImageInfo("alpine", "IfNotPresent")
                        .addResources(EXAMPLE_RESOURCES)
                        .addPodMetadata(() => ({labels: {first: "value"}}))
                        .addEnvVars(env => env.addEnvVar("FIRST", "one"));

                    // @ts-expect-error - addEnvVars must preserve duplicate pod-config checks
                    return withEnv.addPodMetadata(() => ({labels: {second: "value"}}));
                })
            );

        expect(true).toBe(true);
    });
});
