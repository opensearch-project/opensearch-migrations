import * as fs from "node:fs";
import * as path from "node:path";
import {z} from "zod";
import {
    collectProjectedFields,
    collectRestrictedProjectedFields,
    ProjectedField,
    RESOURCE_PROJECTIONS,
    ResourceProjection,
} from "./migrationResourceProjections";

const API_GROUP = "migrations.opensearch.org";
const API_VERSION = "v1alpha1";
const VAP_NAME_PREFIX = "migrations";
const MIGRATION_RUN_PLURAL = "migrationruns";
const RUN_NUMBER_LABEL = `${API_GROUP}/run-number`;
const WORKFLOW_UID_LABEL = `${API_GROUP}/workflow-uid`;
const APPROVED_DURING_RUN_ANNOTATION = `${API_GROUP}/approved-during-run`;
const GENERATED_LABELS = {
    "app.kubernetes.io/part-of": "opensearch-migrations",
    "migrations.opensearch.org/generated": "true",
};

type YamlScalar = string | number | boolean | null;
type YamlValue = YamlScalar | YamlValue[] | {[key: string]: YamlValue};

function isPlainObject(value: unknown): value is Record<string, YamlValue> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function yamlScalar(value: YamlScalar): string {
    if (value === null) return "null";
    if (typeof value === "number" || typeof value === "boolean") return String(value);
    if (value === "true" || value === "false" || value === "null") return JSON.stringify(value);
    if (value === "{}") return "{}";
    if (/^[A-Za-z0-9_.{}[\]\/:"' -]+$/.test(value) && !value.startsWith("{{")) {
        return value;
    }
    return JSON.stringify(value);
}

function toYaml(value: YamlValue, indent = 0): string {
    const pad = " ".repeat(indent);
    if (!isPlainObject(value)) {
        if (Array.isArray(value)) {
            if (value.length === 0) return "[]";
            return value.map(item => {
                if (isPlainObject(item)) {
                    return `${pad}-\n${toYaml(item, indent + 2)}`;
                }
                return `${pad}- ${toYaml(item, 0)}`;
            }).join("\n");
        }
        return yamlScalar(value);
    }

    const entries = Object.entries(value);
    if (entries.length === 0) return "{}";

    return entries.map(([key, child]) => {
        if (isPlainObject(child)) {
            if (Object.keys(child).length === 0) return `${pad}${key}: {}`;
            return `${pad}${key}:\n${toYaml(child, indent + 2)}`;
        }
        if (Array.isArray(child)) {
            if (child.length === 0) return `${pad}${key}: []`;
            if (child.every(item => !isPlainObject(item) && !Array.isArray(item))) {
                return `${pad}${key}: [${child.map(item => yamlScalar(item as YamlScalar)).join(", ")}]`;
            }
            return `${pad}${key}:\n${toYaml(child, indent + 2)}`;
        }
        return `${pad}${key}: ${yamlScalar(child as YamlScalar)}`;
    }).join("\n");
}

function unwrapSchema(schema: z.ZodTypeAny | undefined): z.ZodTypeAny | undefined {
    let current = schema;
    while (current) {
        if (current instanceof z.ZodOptional) {
            current = current.unwrap() as z.ZodTypeAny;
            continue;
        }
        if (current instanceof z.ZodDefault) {
            current = current.removeDefault() as z.ZodTypeAny;
            continue;
        }
        const def = (current as any)._def;
        if (def?.out) {
            current = def.out as z.ZodTypeAny;
            continue;
        }
        if (def?.innerType) {
            current = def.innerType as z.ZodTypeAny;
            continue;
        }
        return current;
    }
    return undefined;
}

function schemaToOpenApi(schema: z.ZodTypeAny | undefined): Record<string, YamlValue> {
    const unwrapped = unwrapSchema(schema);
    if (!unwrapped) return {type: "object", "x-kubernetes-preserve-unknown-fields": true};
    if (unwrapped instanceof z.ZodString) return {type: "string"};
    if (unwrapped instanceof z.ZodBoolean) return {type: "boolean"};
    if (unwrapped instanceof z.ZodNumber) return {type: "number"};
    if (unwrapped instanceof z.ZodEnum) return {type: "string", enum: [...unwrapped.options]};
    if (unwrapped instanceof z.ZodArray) {
        return {
            type: "array",
            items: schemaToOpenApi(unwrapped.element as z.ZodTypeAny),
        };
    }
    return {type: "object", "x-kubernetes-preserve-unknown-fields": true};
}

function putNestedProperty(
    properties: Record<string, any>,
    specPath: string[],
    schema: z.ZodTypeAny | undefined,
) {
    let cursor = properties;
    for (let i = 0; i < specPath.length; i++) {
        const part = specPath[i];
        const last = i === specPath.length - 1;
        if (last) {
            cursor[part] = schemaToOpenApi(schema);
        } else {
            cursor[part] ??= {type: "object", properties: {}};
            cursor = cursor[part].properties;
        }
    }
}

function specPropertiesFor(resource: ResourceProjection): Record<string, YamlValue> {
    const properties: Record<string, YamlValue> = {};
    for (const field of collectProjectedFields().filter(f => f.resourceKind === resource.kind)) {
        putNestedProperty(properties, field.specPath, field.schema);
    }
    return properties;
}

function statusSchemaFor(resource: ResourceProjection): Record<string, YamlValue> {
    const readyPhases = resource.lifecycle === "terminal"
        ? ["Created", "Pending", "Completed", "Deleting", "Error"]
        : resource.lifecycle === "approvalGate"
            ? ["Created", "Pending", "Approved", "Error"]
            : ["Created", "Pending", "Ready", "Deleting", "Error"];

    const common: Record<string, YamlValue> = {
        phase: {type: "string", enum: readyPhases},
        lastAppliedGeneration: {type: "integer"},
    };
    if (resource.lifecycle !== "approvalGate") {
        common.configChecksum = {type: "string"};
    }
    if (resource.kind === "CapturedTraffic") {
        common.checksumForSnapshot = {type: "string"};
        common.checksumForReplayer = {type: "string"};
    }
    if (resource.kind === "CaptureProxy" || resource.kind === "TrafficReplay" || resource.kind === "SnapshotMigration") {
        common.deploymentName = {type: "string"};
    }
    if (resource.kind === "DataSnapshot") {
        common.snapshotName = {type: "string"};
        common.checksumForSnapshotMigration = {type: "string"};
    }
    if (resource.kind === "SnapshotMigration") {
        common.checksumForReplayer = {type: "string"};
        common.outputs = {type: "object", "x-kubernetes-preserve-unknown-fields": true};
    }
    return {
        type: "object",
        properties: common,
        "x-kubernetes-preserve-unknown-fields": true,
    };
}

function singular(kind: string): string {
    return kind.toLowerCase();
}

function resourceDescription(resource: ResourceProjection): string {
    const lifecycle = resource.lifecycle === "terminal"
        ? "Terminal resource."
        : resource.lifecycle === "approvalGate"
            ? "Approval checkpoint resource."
            : "Long-lived resource.";
    return `# ${resource.kind}: generated migration resource. ${lifecycle}`;
}

function crdFor(resource: ResourceProjection): YamlValue {
    return {
        apiVersion: "apiextensions.k8s.io/v1",
        kind: "CustomResourceDefinition",
        metadata: {
            name: `${resource.plural}.${API_GROUP}`,
            labels: GENERATED_LABELS,
        },
        spec: {
            group: API_GROUP,
            names: {
                kind: resource.kind,
                plural: resource.plural,
                singular: singular(resource.kind),
            },
            scope: "Namespaced",
            versions: [{
                name: API_VERSION,
                served: true,
                storage: true,
                subresources: {
                    status: {},
                },
                schema: {
                    openAPIV3Schema: {
                        type: "object",
                        properties: {
                            spec: {
                                type: "object",
                                properties: specPropertiesFor(resource),
                            },
                            status: statusSchemaFor(resource),
                        },
                    },
                },
            }],
        },
    };
}

function migrationRunCrd(): YamlValue {
    const statusSetOnce = (field: string) => [
        "(",
        `  (!has(oldSelf.${field}) && has(self.${field})) ||`,
        `  (has(oldSelf.${field}) && has(self.${field}) && self.${field} == oldSelf.${field})`,
        ")",
    ].join(" ");
    return {
        apiVersion: "apiextensions.k8s.io/v1",
        kind: "CustomResourceDefinition",
        metadata: {
            name: `${MIGRATION_RUN_PLURAL}.${API_GROUP}`,
            labels: GENERATED_LABELS,
        },
        spec: {
            group: API_GROUP,
            names: {
                kind: "MigrationRun",
                plural: MIGRATION_RUN_PLURAL,
                singular: "migrationrun",
                shortNames: ["mrun"],
            },
            scope: "Namespaced",
            versions: [{
                name: API_VERSION,
                served: true,
                storage: true,
                subresources: {
                    status: {},
                },
                additionalPrinterColumns: [
                    {name: "Workflow", type: "string", jsonPath: ".spec.workflowName"},
                    {name: "Run", type: "integer", jsonPath: ".spec.runNumber"},
                    {name: "Timestamp", type: "date", jsonPath: ".spec.timestamp"},
                    {name: "Workflow UID", type: "string", jsonPath: ".status.workflowUid"},
                ],
                schema: {
                    openAPIV3Schema: {
                        type: "object",
                        properties: {
                            spec: {
                                type: "object",
                                required: ["workflowName", "runNumber", "timestamp", "resolvedConfig"],
                                "x-kubernetes-validations": [{
                                    rule: "self == oldSelf",
                                    message: "MigrationRun specs are historical records and are immutable after creation.",
                                }],
                                properties: {
                                    runNumber: {
                                        type: "integer",
                                        minimum: 0,
                                    },
                                    timestamp: {
                                        type: "string",
                                        format: "date-time",
                                    },
                                    workflowName: {
                                        type: "string",
                                    },
                                    resolvedConfig: {
                                        type: "object",
                                        "x-kubernetes-preserve-unknown-fields": true,
                                    },
                                },
                            },
                            status: {
                                type: "object",
                                "x-kubernetes-validations": [{
                                    rule: `${statusSetOnce("workflowUid")} && ${statusSetOnce("workflowCreationTimestamp")}`,
                                    message: "MigrationRun workflow status fields may only be set once.",
                                }],
                                properties: {
                                    workflowUid: {
                                        type: "string",
                                    },
                                    workflowCreationTimestamp: {
                                        type: "string",
                                        format: "date-time",
                                    },
                                },
                            },
                        },
                    },
                },
            }],
        },
    };
}

export function generateMigrationCrdsYaml(): string {
    const generatedCrds = [
        ...RESOURCE_PROJECTIONS
            .map(resource => `${resourceDescription(resource)}\n${toYaml(crdFor(resource))}`),
        `# MigrationRun: generated historical record resource. Specs are immutable after creation.\n${toYaml(migrationRunCrd())}`,
    ];
    return generatedCrds
        .join("\n---\n") + "\n";
}

function celPath(path: string[], root = "object"): string {
    return [`${root}.spec`, ...path].join(".");
}

function hasChain(path: string[], root: "object" | "oldObject"): string[] {
    const pieces: string[] = [];
    for (let i = 0; i < path.length; i++) {
        pieces.push(`has(${[`${root}.spec`, ...path.slice(0, i + 1)].join(".")})`);
    }
    return pieces;
}

function presentExpression(path: string[], root: "object" | "oldObject"): string {
    return hasChain(path, root).join(" && ");
}

function equalityExpression(field: ProjectedField): string {
    const oldPresent = presentExpression(field.specPath, "oldObject");
    const newPresent = presentExpression(field.specPath, "object");
    return `(!(${oldPresent}) && !(${newPresent})) || ((${oldPresent}) && (${newPresent}) && ${celPath(field.specPath)} == ${celPath(field.specPath, "oldObject")})`;
}

function nonDecreasingExpression(field: ProjectedField): string {
    const oldPresent = presentExpression(field.specPath, "oldObject");
    const newPresent = presentExpression(field.specPath, "object");
    return `!(${oldPresent}) || !(${newPresent}) || ${celPath(field.specPath)} >= ${celPath(field.specPath, "oldObject")}`;
}

function indentBlock(text: string, spaces: number): string {
    const pad = " ".repeat(spaces);
    return text.split("\n").map(line => `${pad}${line}`).join("\n");
}

function approvalExpression(): string {
    return [
        "has(object.metadata.annotations)",
        `'${APPROVED_DURING_RUN_ANNOTATION}' in object.metadata.annotations`,
        "has(object.metadata.labels)",
        `'${RUN_NUMBER_LABEL}' in object.metadata.labels`,
        `object.metadata.annotations['${APPROVED_DURING_RUN_ANNOTATION}'] == object.metadata.labels['${RUN_NUMBER_LABEL}']`,
    ].join(" &&\n");
}

function validationRule(expression: string, message: string): string {
    return [
        "    - expression: |",
        indentBlock(expression, 8),
        `      message: ${JSON.stringify(message)}`,
    ].join("\n");
}

function policyBinding(name: string): string {
    return [
        "---",
        "apiVersion: admissionregistration.k8s.io/v1",
        "kind: ValidatingAdmissionPolicyBinding",
        "metadata:",
        `  name: ${VAP_NAME_PREFIX}-${name}-binding`,
        "  labels:",
        ...Object.entries(GENERATED_LABELS).map(([key, value]) => `    ${key}: ${JSON.stringify(value)}`),
        "spec:",
        `  policyName: ${VAP_NAME_PREFIX}-${name}-policy`,
        "  validationActions: [Deny]",
    ].join("\n");
}

function policyFor(resource: ResourceProjection, fields: ProjectedField[]): string | undefined {
    const restrictedFields = fields.filter(field =>
        field.changeRestriction === "gated" || field.changeRestriction === "impossible"
    );
    if (restrictedFields.length === 0) return undefined;

    const impossible = restrictedFields.filter(field => field.changeRestriction === "impossible");
    const gated = restrictedFields.filter(field => field.changeRestriction === "gated");
    const validations: string[] = [];
    const firstPopulationAllowed =
        "has(oldObject.status) && has(oldObject.status.phase) && oldObject.status.phase == 'Created'";

    for (const field of impossible) {
        validations.push(validationRule(
            `(${firstPopulationAllowed}) ||\n(${equalityExpression(field)})`,
            `Impossible: ${field.specPath.join(".")} cannot be changed. Delete and recreate.`
        ));
    }

    for (const field of gated.filter(field => field.invariant === "nonDecreasing")) {
        validations.push(validationRule(
            `(${firstPopulationAllowed}) ||\n(${nonDecreasingExpression(field)})`,
            `Impossible: ${field.specPath.join(".")} cannot decrease.`
        ));
    }

    if (gated.length > 0) {
        const gatedEquality = gated.map(equalityExpression).map(expr => `(${expr})`).join(" &&\n");
        const expression = `(${firstPopulationAllowed}) ||\n(\n${indentBlock(gatedEquality, 2)}\n) ||\n(${indentBlock(approvalExpression(), 2)}\n)`;
        validations.push(validationRule(
            expression,
            `Gated changes detected on ${resource.kind} fields: ${gated.map(field => field.specPath.join(".")).join(", ")}. Approve the corresponding ApprovalGate to proceed.`
        ));
    }

    const policyName = resource.kind.toLowerCase();
    return [
        "---",
        "apiVersion: admissionregistration.k8s.io/v1",
        "kind: ValidatingAdmissionPolicy",
        "metadata:",
        `  name: ${VAP_NAME_PREFIX}-${policyName}-policy`,
        "  labels:",
        ...Object.entries(GENERATED_LABELS).map(([key, value]) => `    ${key}: ${JSON.stringify(value)}`),
        "spec:",
        "  failurePolicy: Fail",
        "  matchConstraints:",
        "    resourceRules:",
        `    - apiGroups: ["${API_GROUP}"]`,
        `      apiVersions: ["${API_VERSION}"]`,
        `      operations: ["UPDATE"]`,
        `      resources: ["${resource.plural}"]`,
        "  validations:",
        validations.join("\n"),
        "",
        policyBinding(policyName),
    ].join("\n");
}

function lockOnCompletePolicy(): string {
    const terminalResources = RESOURCE_PROJECTIONS
        .filter(resource => resource.terminalPhase === "Completed")
        .map(resource => resource.plural);
    return [
        "---",
        "apiVersion: admissionregistration.k8s.io/v1",
        "kind: ValidatingAdmissionPolicy",
        "metadata:",
        `  name: ${VAP_NAME_PREFIX}-lock-on-complete-policy`,
        "  labels:",
        ...Object.entries(GENERATED_LABELS).map(([key, value]) => `    ${key}: ${JSON.stringify(value)}`),
        "spec:",
        "  failurePolicy: Fail",
        "  matchConstraints:",
        "    resourceRules:",
        `    - apiGroups: ["${API_GROUP}"]`,
        `      apiVersions: ["${API_VERSION}"]`,
        `      operations: ["UPDATE"]`,
        `      resources: [${terminalResources.map(r => JSON.stringify(r)).join(", ")}]`,
        "  validations:",
        validationRule(
            "!has(oldObject.status) ||\n!has(oldObject.status.phase) ||\noldObject.status.phase != 'Completed' ||\n(object.spec == oldObject.spec)",
            "Consistency Guard: This resource is 'Completed'. The specification is permanently sealed. Delete the resource to run a new job with these parameters."
        ),
        "",
        policyBinding("lock-on-complete"),
    ].join("\n");
}

function managedDeploymentPolicy(): string {
    return [
        "---",
        "apiVersion: admissionregistration.k8s.io/v1",
        "kind: ValidatingAdmissionPolicy",
        "metadata:",
        `  name: ${VAP_NAME_PREFIX}-managed-deployment-policy`,
        "  labels:",
        ...Object.entries(GENERATED_LABELS).map(([key, value]) => `    ${key}: ${JSON.stringify(value)}`),
        "spec:",
        "  failurePolicy: Fail",
        "  matchConstraints:",
        "    resourceRules:",
        "    - apiGroups: [\"apps\"]",
        "      apiVersions: [\"v1\"]",
        "      operations: [\"UPDATE\"]",
        "      resources: [\"deployments\"]",
        "  matchConditions:",
        "    - name: is-managed",
        "      expression: |",
        "        has(object.metadata.annotations) &&",
        "        'migrations.opensearch.org/managed-by' in object.metadata.annotations",
        "  validations:",
        validationRule(
            "has(object.metadata.annotations) &&\n'migrations.opensearch.org/allow-direct-edit' in object.metadata.annotations &&\nobject.metadata.annotations['migrations.opensearch.org/allow-direct-edit'] == 'true'",
            "Direct edit blocked: This Deployment is workflow-managed. Edit the corresponding CRD instead."
        ),
        "",
        policyBinding("managed-deployment"),
    ].join("\n");
}

function deletingPhaseGuardPolicy(): string {
    const resources = RESOURCE_PROJECTIONS
        .filter(resource => resource.lifecycle !== "approvalGate")
        .map(resource => resource.plural);
    return [
        "---",
        "apiVersion: admissionregistration.k8s.io/v1",
        "kind: ValidatingAdmissionPolicy",
        "metadata:",
        `  name: ${VAP_NAME_PREFIX}-deleting-phase-guard-policy`,
        "  labels:",
        ...Object.entries(GENERATED_LABELS).map(([key, value]) => `    ${key}: ${JSON.stringify(value)}`),
        "spec:",
        "  failurePolicy: Fail",
        "  matchConstraints:",
        "    resourceRules:",
        `    - apiGroups: ["${API_GROUP}"]`,
        `      apiVersions: ["${API_VERSION}"]`,
        `      operations: ["UPDATE"]`,
        `      resources: [${resources.map(r => JSON.stringify(r)).join(", ")}]`,
        "  validations:",
        validationRule(
            "!has(oldObject.status) ||\n!has(oldObject.status.phase) ||\noldObject.status.phase != 'Deleting'",
            "This resource is being deleted. No updates are permitted during teardown."
        ),
        "",
        policyBinding("deleting-phase-guard"),
    ].join("\n");
}

function migrationRunImmutabilityPolicy(): string {
    const annotationsUnchanged = "((!has(object.metadata.annotations) && !has(oldObject.metadata.annotations)) || (has(object.metadata.annotations) && has(oldObject.metadata.annotations) && object.metadata.annotations == oldObject.metadata.annotations))";
    const labelsUnchanged = "((!has(object.metadata.labels) && !has(oldObject.metadata.labels)) || (has(object.metadata.labels) && has(oldObject.metadata.labels) && object.metadata.labels == oldObject.metadata.labels))";
    const labelsOnlyAddWorkflowUid = [
        "has(object.metadata.labels)",
        `'${WORKFLOW_UID_LABEL}' in object.metadata.labels`,
        `object.metadata.labels['${WORKFLOW_UID_LABEL}'] != ''`,
        `(!has(oldObject.metadata.labels) || !('${WORKFLOW_UID_LABEL}' in oldObject.metadata.labels))`,
        `object.metadata.labels.all(key, key == '${WORKFLOW_UID_LABEL}' || (has(oldObject.metadata.labels) && key in oldObject.metadata.labels && object.metadata.labels[key] == oldObject.metadata.labels[key]))`,
        "(!has(oldObject.metadata.labels) || oldObject.metadata.labels.all(key, key in object.metadata.labels && object.metadata.labels[key] == oldObject.metadata.labels[key]))",
    ].join(" &&\n");
    const workflowUidLabelSetOnce = [
        `!has(oldObject.metadata.labels) ||`,
        `!('${WORKFLOW_UID_LABEL}' in oldObject.metadata.labels) ||`,
        "(",
        "  has(object.metadata.labels) &&",
        `  '${WORKFLOW_UID_LABEL}' in object.metadata.labels &&`,
        `  object.metadata.labels['${WORKFLOW_UID_LABEL}'] == oldObject.metadata.labels['${WORKFLOW_UID_LABEL}']`,
        ")",
    ].join("\n");
    return [
        "---",
        "apiVersion: admissionregistration.k8s.io/v1",
        "kind: ValidatingAdmissionPolicy",
        "metadata:",
        `  name: ${VAP_NAME_PREFIX}-migrationrun-immutability-policy`,
        "  labels:",
        ...Object.entries(GENERATED_LABELS).map(([key, value]) => `    ${key}: ${JSON.stringify(value)}`),
        "spec:",
        "  failurePolicy: Fail",
        "  matchConstraints:",
        "    resourceRules:",
        `    - apiGroups: ["${API_GROUP}"]`,
        `      apiVersions: ["${API_VERSION}"]`,
        `      operations: ["UPDATE"]`,
        `      resources: ["${MIGRATION_RUN_PLURAL}"]`,
        "  validations:",
        validationRule(
            "object.spec == oldObject.spec &&\n" +
            `${annotationsUnchanged} &&\n` +
            `(${labelsUnchanged} || (${labelsOnlyAddWorkflowUid}))`,
            "MigrationRun records are historical records and are immutable after creation."
        ),
        validationRule(
            workflowUidLabelSetOnce,
            "MigrationRun workflow UID label may only be set once."
        ),
        "",
        policyBinding("migrationrun-immutability"),
    ].join("\n");
}

function migrationRunStatusImmutabilityPolicy(): string {
    const setOnce = (field: string) => [
        "(",
        `  ((!has(oldObject.status) || !has(oldObject.status.${field})) && has(object.status) && has(object.status.${field})) ||`,
        `  (has(oldObject.status) && has(oldObject.status.${field}) && has(object.status) && has(object.status.${field}) && object.status.${field} == oldObject.status.${field})`,
        ")",
    ].join("\n");
    return [
        "---",
        "apiVersion: admissionregistration.k8s.io/v1",
        "kind: ValidatingAdmissionPolicy",
        "metadata:",
        `  name: ${VAP_NAME_PREFIX}-migrationrun-status-immutability-policy`,
        "  labels:",
        ...Object.entries(GENERATED_LABELS).map(([key, value]) => `    ${key}: ${JSON.stringify(value)}`),
        "spec:",
        "  failurePolicy: Fail",
        "  matchConstraints:",
        "    resourceRules:",
        `    - apiGroups: ["${API_GROUP}"]`,
        `      apiVersions: ["${API_VERSION}"]`,
        `      operations: ["UPDATE"]`,
        `      resources: ["${MIGRATION_RUN_PLURAL}/status"]`,
        "  validations:",
        validationRule(
            `${setOnce("workflowUid")} &&\n${setOnce("workflowCreationTimestamp")}`,
            "MigrationRun workflow status fields may only be set once."
        ),
        "",
        policyBinding("migrationrun-status-immutability"),
    ].join("\n");
}

export function generateValidatingAdmissionPoliciesYaml(): string {
    const fieldsByResource = new Map<string, ProjectedField[]>();
    for (const field of collectRestrictedProjectedFields()) {
        const fields = fieldsByResource.get(field.resourceKind) ?? [];
        fields.push(field);
        fieldsByResource.set(field.resourceKind, fields);
    }

    const policies = RESOURCE_PROJECTIONS
        .map(resource => policyFor(resource, fieldsByResource.get(resource.kind) ?? []))
        .filter((policy): policy is string => policy !== undefined);

    return [
        "# Generated ValidatingAdmissionPolicies for migration resources.",
        ...policies,
        lockOnCompletePolicy(),
        managedDeploymentPolicy(),
        deletingPhaseGuardPolicy(),
        migrationRunImmutabilityPolicy(),
        migrationRunStatusImmutabilityPolicy(),
    ].join("\n\n") + "\n";
}

function writeFileIfRequested(contents: string, outputPath: string | undefined) {
    if (!outputPath) {
        process.stdout.write(contents);
        return;
    }
    fs.mkdirSync(path.dirname(outputPath), {recursive: true});
    fs.writeFileSync(outputPath, contents, "utf8");
}

export function main(args = process.argv.slice(2)) {
    const outputIndex = args.indexOf("--output");
    const outputPath = outputIndex >= 0 ? args[outputIndex + 1] : undefined;
    const outputDirIndex = args.findIndex(arg => arg === "--output-dir" || arg === "--outputDirectory");
    const outputDir = outputDirIndex >= 0 ? args[outputDirIndex + 1] : undefined;
    if (args.includes("--all")) {
        if (!outputDir) {
            throw new Error("Usage: generate-migration-resources --all --output-dir <path>");
        }
        fs.mkdirSync(outputDir, {recursive: true});
        fs.writeFileSync(path.join(outputDir, "migrationCrds.yaml"), generateMigrationCrdsYaml(), "utf8");
        fs.writeFileSync(
            path.join(outputDir, "validatingAdmissionPolicies.yaml"),
            generateValidatingAdmissionPoliciesYaml(),
            "utf8"
        );
        return;
    }
    if (args.includes("--crds")) {
        writeFileIfRequested(generateMigrationCrdsYaml(), outputPath);
        return;
    }
    if (args.includes("--vaps")) {
        writeFileIfRequested(generateValidatingAdmissionPoliciesYaml(), outputPath);
        return;
    }
    throw new Error("Usage: generate-migration-resources (--crds|--vaps) [--output <path>] | --all --output-dir <path>");
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main();
}
