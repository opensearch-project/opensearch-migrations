import * as fs from "fs";
import * as path from "path";
import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {CreateSnapshot} from "../src/workflowTemplates/createSnapshot";
import {DocumentBulkLoad} from "../src/workflowTemplates/documentBulkLoad";
import {MetadataMigration} from "../src/workflowTemplates/metadataMigration";
import {Replayer} from "../src/workflowTemplates/replayer";
import {RfsCoordinatorCluster} from "../src/workflowTemplates/rfsCoordinatorCluster";
import {S3TrafficLoader} from "../src/workflowTemplates/s3TrafficLoader";
import {SetupCapture} from "../src/workflowTemplates/setupCapture";
import {SetupKafka} from "../src/workflowTemplates/setupKafka";
import {MIGRATION_RESOURCE_UID_LABEL} from "../src/workflowTemplates/commonUtils/resourceLabels";

const RESOURCE_UID_LABEL = MIGRATION_RESOURCE_UID_LABEL;

function template(workflow: unknown, name: string): any {
    const rendered = renderWorkflowTemplate(workflow as any) as any;
    const result = rendered.spec.templates.find((candidate: any) => candidate.name === name);
    expect(result).toBeDefined();
    return result;
}

function resourceManifest(workflow: unknown, name: string): string {
    const result = template(workflow, name);
    expect(result.resource?.manifest).toBeDefined();
    return result.resource.manifest;
}

function countOccurrences(value: string, search: string): number {
    return value.split(search).length - 1;
}

describe("migration resource log ownership", () => {
    it.each([
        [CreateSnapshot, "runcreatesnapshot", "{{inputs.parameters.dataSnapshotUid}}"],
        [MetadataMigration, "runmetadata", "{{inputs.parameters.crdUid}}"],
        [S3TrafficLoader, "loads3intotopic", "{{inputs.parameters.resourceUid}}"],
    ])(
        "labels direct workload pods with their migration CR UID",
        (workflow, templateName, expectedUid) => {
            expect(template(workflow, templateName).metadata?.labels?.[RESOURCE_UID_LABEL])
                .toBe(expectedUid);
        },
    );

    it.each([
        [SetupCapture, "deployproxydeployment", "ownerUid"],
        [SetupCapture, "deployproxydeploymentwithtls", "ownerUid"],
        [Replayer, "createdeployment", "ownerUid"],
        [DocumentBulkLoad, "createrfsdeployment", "crdUid"],
        [RfsCoordinatorCluster, "createrfscoordinatorstatefulset", "ownerUid"],
    ])(
        "labels controller-created pods with their migration CR UID",
        (workflow, templateName, uidParameter) => {
            const manifest = resourceManifest(workflow, templateName);
            const expectedLabel =
                `${RESOURCE_UID_LABEL}: {{=toJSON(inputs.parameters.${uidParameter})}}`;
            expect(countOccurrences(manifest, expectedLabel)).toBe(2);
        },
    );

    it("propagates KafkaCluster ownership through Strimzi pod templates", () => {
        const nodePool = resourceManifest(SetupKafka, "deploykafkanodepool");
        const kafka = resourceManifest(SetupKafka, "deploykafkaclusterkraftnoauth");

        expect(countOccurrences(nodePool, RESOURCE_UID_LABEL)).toBe(2);
        expect(nodePool).toContain("inputs.parameters.ownerUid");
        expect(countOccurrences(kafka, RESOURCE_UID_LABEL)).toBe(2);
        expect(kafka).toContain("inputs.parameters.ownerUid");
    });

    it.each([
        ["applySnapshotMonitorCronJob.sh", "DATASNAPSHOT_UID"],
        ["applyRfsMonitorCronJob.sh", "SM_UID"],
    ])(
        "labels monitor CronJob pods with their migration CR UID",
        (scriptName, uidVariable) => {
            const script = fs.readFileSync(
                path.resolve(__dirname, `../resources/scripts/${scriptName}`),
                "utf8",
            );
            expect(script).toContain(`RESOURCE_UID_LABEL="${RESOURCE_UID_LABEL}"`);
            expect(script).toContain('--arg resourceuidkey "$RESOURCE_UID_LABEL"');
            expect(countOccurrences(
                script,
                `\${RESOURCE_UID_LABEL}: "\${${uidVariable}}"`,
            )).toBe(3);
        },
    );
});
