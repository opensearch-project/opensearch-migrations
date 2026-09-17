import {expectTypeOf} from "expect-type";
import {
    ContainerBuilder,
    OutputArtifactDef,
    renderWorkflowTemplate,
    WorkflowBuilder
} from "../../src";

type ArtifactScopeOf<Builder> =
    Builder extends ContainerBuilder<any, any, any, any, any, any, any, infer ArtifactScope>
        ? ArtifactScope
        : never;

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
