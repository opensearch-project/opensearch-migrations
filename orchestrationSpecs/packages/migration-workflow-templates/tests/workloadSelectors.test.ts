import {parse as parseYaml} from "yaml";
import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {DocumentBulkLoad} from "../src/workflowTemplates/documentBulkLoad";
import {Replayer} from "../src/workflowTemplates/replayer";
import {RfsCoordinatorCluster} from "../src/workflowTemplates/rfsCoordinatorCluster";
import {SetupCapture} from "../src/workflowTemplates/setupCapture";
import {
    COMPONENT_LABEL,
    COMPONENTS,
    MIGRATION_RESOURCE_UID_LABEL,
} from "../src/workflowTemplates/commonUtils/resourceLabels";

// The rules from docs/kubernetesLabels.md: every object of one component selects the same pods,
// that set is one instance (the selector carries the owning CR's UID), and the pods carry the
// shared labels that log filters and tools rely on.

const SHARED_POD_LABELS = [
    "workflows.argoproj.io/workflow",
    "migrations.opensearch.org/task",
    COMPONENT_LABEL,
    MIGRATION_RESOURCE_UID_LABEL,
];

type ComponentCase = {
    component: string,
    workflow: unknown,
    workloads: string[],
    otherSelectingObjects: string[],
    relationshipLabels: string[],
};

const RFS_RELATIONSHIP_LABELS = [
    "migrations.opensearch.org/source",
    "migrations.opensearch.org/target",
    "migrations.opensearch.org/snapshot",
    "migrations.opensearch.org/from-snapshot-migration",
];

const CASES: ComponentCase[] = [
    {
        component: COMPONENTS.CAPTURE_PROXY,
        workflow: SetupCapture,
        workloads: ["deployproxydeployment", "deployproxydeploymentwithtls"],
        otherSelectingObjects: ["deployproxyservice", "deployproxypoddisruptionbudget"],
        relationshipLabels: ["migrations.opensearch.org/source"],
    },
    {
        component: COMPONENTS.TRAFFIC_REPLAYER,
        workflow: Replayer,
        workloads: ["createdeployment"],
        otherSelectingObjects: ["createpoddisruptionbudget"],
        relationshipLabels: ["migrations.opensearch.org/source", "migrations.opensearch.org/target"],
    },
    {
        component: COMPONENTS.RFS_WORKER,
        workflow: DocumentBulkLoad,
        workloads: ["createrfsdeployment"],
        otherSelectingObjects: ["createrfspoddisruptionbudget"],
        relationshipLabels: RFS_RELATIONSHIP_LABELS,
    },
    {
        component: COMPONENTS.RFS_COORDINATOR,
        workflow: RfsCoordinatorCluster,
        workloads: ["createrfscoordinatorstatefulset"],
        otherSelectingObjects: ["createrfscoordinatorservice", "createrfscoordinatorpoddisruptionbudget"],
        relationshipLabels: RFS_RELATIONSHIP_LABELS,
    },
];

// Rendered manifests hold Argo expressions as bare `{{...}}` scalars, which YAML would read as
// flow mappings. Quote them so the manifest parses; the expression text is kept for comparison.
function parseManifest(manifest: string): any {
    const quoted = manifest.replace(/: (\{\{.*\}\})$/gm, (_, expression) => `: ${JSON.stringify(expression)}`);
    return parseYaml(quoted);
}

function manifestFor(workflow: unknown, templateName: string): any {
    const rendered = renderWorkflowTemplate(workflow as any) as any;
    const found = rendered.spec.templates.find((candidate: any) => candidate.name === templateName);
    expect(found?.resource?.manifest).toBeDefined();
    return parseManifest(found.resource.manifest);
}

function selectorOf(manifest: any): Record<string, string> {
    return manifest.kind === "Service" ? manifest.spec.selector : manifest.spec.selector.matchLabels;
}

describe.each(CASES)("$component workload labels and selectors", c => {
    const workloads = c.workloads.map(name => manifestFor(c.workflow, name));
    const selectingObjects = [...workloads, ...c.otherSelectingObjects.map(name => manifestFor(c.workflow, name))];

    it("selects the same pods from every object", () => {
        const [first, ...rest] = selectingObjects.map(selectorOf);
        rest.forEach(selector => expect(selector).toEqual(first));
    });

    it("selects one instance: the pods owned by one migration CR", () => {
        selectingObjects.forEach(manifest =>
            expect(selectorOf(manifest)[MIGRATION_RESOURCE_UID_LABEL]).toMatch(/inputs\.parameters\.\w+Uid/));
    });

    it("selects on exactly {component, migration-resource-uid}", () => {
        selectingObjects.forEach(manifest =>
            expect(Object.keys(selectorOf(manifest)).sort())
                .toEqual([COMPONENT_LABEL, MIGRATION_RESOURCE_UID_LABEL].sort()));
        expect(selectorOf(workloads[0])[COMPONENT_LABEL]).toBe(c.component);
    });

    it("labels its pods with everything its selectors match", () => {
        workloads.forEach(workload => {
            const podLabels = workload.spec.template.metadata.labels;
            Object.entries(selectorOf(workload)).forEach(([key, value]) => expect(podLabels[key]).toBe(value));
        });
    });

    it("labels its pods with the shared labels", () => {
        workloads.forEach(workload => {
            const podLabels = workload.spec.template.metadata.labels;
            [...SHARED_POD_LABELS, ...c.relationshipLabels].forEach(key => expect(podLabels).toHaveProperty([key]));
        });
    });
});
