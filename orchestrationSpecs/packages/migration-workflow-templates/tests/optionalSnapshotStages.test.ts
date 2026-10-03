import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {FullMigration} from "../src/workflowTemplates/fullMigration";

describe("optional snapshot migration stages", () => {
    const rendered = renderWorkflowTemplate(FullMigration) as any;
    const runSingleSnapshotMigration = rendered.spec.templates.find(
        (template: any) => template.name === "runsinglesnapshotmigration"
    );
    const migrateFromSnapshot = runSingleSnapshotMigration.steps
        .flat()
        .find((step: any) => step.name === "migrateFromSnapshot");
    const argumentsByName = Object.fromEntries(
        migrateFromSnapshot.arguments.parameters.map((parameter: any) => [parameter.name, parameter.value])
    );

    test.each([
        "metadataMigrationConfig",
        "documentBackfillConfig",
    ])("preserves absence of %s as the empty-string skip sentinel", configName => {
        const value = argumentsByName[configName] as string;

        expect(value).toContain(`'${configName}' in fromJSON(inputs.parameters.snapshotMigrationConfig)`);
        expect(value).toContain(
            `toJSON(fromJSON(inputs.parameters.snapshotMigrationConfig)['${configName}'])`
        );
        expect(value).toMatch(/: \(''\)\)}}$/);
        expect(value).not.toContain(`sprig.dig('${configName}', sprig.dict()`);
    });

    test("migrateFromSnapshot gates each stage on its empty-string sentinel", () => {
        const template = rendered.spec.templates.find(
            (candidate: any) => candidate.name === "migratefromsnapshot"
        );
        const stepsByName = Object.fromEntries(
            template.steps.flat().map((step: any) => [step.name, step])
        );

        expect(stepsByName.metadataMigrate.when)
            .toBe("{{=!(0 == len(inputs.parameters.metadataMigrationConfig))}}");
        expect(stepsByName.bulkLoadDocuments.when)
            .toBe("{{=!(0 == len(inputs.parameters.documentBackfillConfig))}}");
    });
});
