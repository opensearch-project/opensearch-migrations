import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {z} from "zod";
import {FullMigration} from "../src/workflowTemplates/fullMigration";
import {ResourceManagement} from "../src/workflowTemplates/resourceManagement";

const parameter = z.object({name: z.string(), value: z.string().optional()});
const step = z.object({
    name: z.string(),
    template: z.string().optional(),
    when: z.string().optional(),
    arguments: z.object({parameters: z.array(parameter)}).optional(),
});
const template = z.object({
    name: z.string(),
    steps: z.array(z.array(step)).optional(),
    resource: z.object({
        manifest: z.string(),
        successCondition: z.string().optional(),
        failureCondition: z.string().optional(),
    }).optional(),
});
const workflow = z.object({spec: z.object({templates: z.array(template)})});
const templates = [
    ...workflow.parse(renderWorkflowTemplate(FullMigration)).spec.templates,
    ...workflow.parse(renderWorkflowTemplate(ResourceManagement)).spec.templates,
];

function getTemplate(name: string) {
    const result = templates.find(candidate => candidate.name === name);
    if (!result) throw new Error(`Missing template ${name}`);
    return result;
}

function getStep(templateName: string, stepName: string) {
    const result = getTemplate(templateName).steps?.flat().find(candidate => candidate.name === stepName);
    if (!result) throw new Error(`Missing step ${templateName}/${stepName}`);
    return result;
}

describe("successive snapshot workflow execution", () => {
    it("serializes snapshot creation, backfill, approval, and durable completion before recursing", () => {
        const sequence = getTemplate("runsnapshotsequence");
        expect(sequence.steps?.map(group => group.map(step => step.name))).toEqual([
            ["createSnapshot"], ["backfill"], ["readCheckpoint"], ["approveBackfill"], ["saveCheckpoint"], ["next"],
        ]);
        const next = getStep(sequence.name, "next");
        expect(next.template).toBe(sequence.name);
        expect(next.when).toContain("asInt(fromJSON(inputs.parameters.stepIndex)) + 1 < len(");
        expect(getStep(sequence.name, "createSnapshot").when).toContain("'snapshotCreation' in");
        expect(getStep(sequence.name, "backfill").arguments?.parameters.find(p => p.name === "snapshotMigrationConfig")?.value)
            .toContain("['snapshotMigrations'][asInt(");
        // withParam already serializes nested arrays; a second toJSON would pass a string.
        expect(getStep("main", "performSnapshotSequence").arguments?.parameters.find(p => p.name === "sequenceSteps")?.value)
            .toBe("{{=item['steps']}}");
    });

    it("keeps sequence resources out of the independent concurrent loops", () => {
        expect(getStep("createindependentsnapshot", "createSnapshot").when).toContain(
            "len(sprig.dig('sequenceName', '', fromJSON(inputs.parameters.snapshotItemConfig)))");
        expect(getStep("runindependentsnapshotmigration", "backfill").when).toContain(
            "len(sprig.dig('sequenceName', '', fromJSON(inputs.parameters.snapshotMigrationConfig)))");
    });

    it("uses the previous snapshot's resolved name and phase in the worker arguments", () => {
        const args = getStep("runsinglesnapshotmigration", "migrateFromSnapshot").arguments?.parameters;
        const backfill = args?.find(p => p.name === "documentBackfillConfig")?.value;
        expect(backfill).toContain("previousSnapshotName");
        expect(backfill).toContain("steps.readPreviousSnapshotName.outputs.parameters.snapshotName");
        expect(backfill).toContain("deltaMode");
        expect(getStep("runsinglesnapshotmigration", "readPreviousSnapshotName").when)
            .toContain("'delta', 'previousSnapshotNameResolution', 'dataSnapshotResourceName'");
    });

    it("only populates the new immutable CR fields when their inputs exist", () => {
        const manifest = getTemplate("upsertsnapshotmigrationresource").resource?.manifest;
        expect(manifest).toContain("'delta' in fromJSON(inputs.parameters.snapshotMigrationConfig)");
        expect(manifest).toContain("'previousMigrationResourceName' in fromJSON(inputs.parameters.snapshotMigrationConfig)");
    });

    it("does not skip an unapproved backfill on restart or accept a partially successful baseline", () => {
        const checkpoint = getTemplate("readsnapshotsequencecheckpoint").resource;
        expect(checkpoint?.successCondition).toBe("status.documentBackfill.phase == Completed");
        expect(checkpoint?.failureCondition).toBe("status.documentBackfill.phase == CompletedWithErrors");
        expect(getStep("runsnapshotsequence", "approveBackfill").when)
            .toContain("steps.readCheckpoint.outputs.parameters.completedChecksum");
        expect(getTemplate("patchsnapshotsequencecheckpoint").resource?.manifest)
            .toContain("sequenceCompletionChecksum:");
        expect(getTemplate("patchsnapshotsequencecheckpoint").resource?.manifest)
            .toContain("checksumForReplayer:");
        expect(getStep("runsinglesnapshotmigration", "migrateFromSnapshot").arguments?.parameters
            .find(p => p.name === "checksumForReplayer")?.value)
            .toContain("'sequenceName' in");
        expect(getStep("migratefromsnapshot", "approveBackfill").when)
            .toContain("skipBackfillApproval");
    });
});
