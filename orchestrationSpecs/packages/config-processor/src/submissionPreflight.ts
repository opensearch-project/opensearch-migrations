import {spawnSync} from "child_process";
import * as fs from "fs/promises";
import * as path from "path";
import {stringify} from "yaml";
import {
    dryRunResourcePolicy,
    ResolvedMigrationResource,
} from "./resolvedMigrationResources";

export type SubmissionPreflightClassification =
    | "approval-required"
    | "recreate-required"
    | "invalid"
    | "warning";

export interface SubmissionPreflightIssue {
    kind: string;
    name: string;
    plural?: string;
    classification: SubmissionPreflightClassification;
    blocking: boolean;
    message: string;
    source: "kubernetes" | "projection-policy" | "preflight";
    resourceId?: string;
    resetTargetId?: string;
}

export interface SubmissionPreflightReport {
    formatVersion: 1;
    allowed: boolean;
    checkedResources: number;
    issues: SubmissionPreflightIssue[];
}

export interface SubmissionPreflightResource {
    manifest: {
        apiVersion: string;
        kind: string;
        metadata: Record<string, unknown> & {name: string};
        spec?: Record<string, unknown>;
        [key: string]: unknown;
    };
    policyResource?: ResolvedMigrationResource;
}

export interface SubmissionCommandResult {
    status: number;
    stdout: string;
    stderr: string;
}

export type SubmissionCommandRunner = (
    args: string[],
    input?: string,
) => SubmissionCommandResult;

const KIND_TO_PLURAL: Record<string, string> = {
    ApprovalGate: "approvalgates",
    CaptureProxy: "captureproxies",
    CapturedTraffic: "capturedtraffics",
    ConfigMap: "configmaps",
    DataSnapshot: "datasnapshots",
    KafkaCluster: "kafkaclusters",
    MigrationRun: "migrationruns",
    SnapshotMigration: "snapshotmigrations",
    TrafficReplay: "trafficreplays",
};

const BLOCKING_CLASSIFICATIONS = new Set<SubmissionPreflightClassification>([
    "recreate-required",
    "invalid",
]);

function defaultRunner(args: string[], input?: string): SubmissionCommandResult {
    const result = spawnSync("kubectl", args, {
        input,
        encoding: "utf8",
    });
    return {
        status: result.status ?? 1,
        stdout: result.stdout ?? "",
        stderr: result.stderr || result.error?.message || "",
    };
}

function apiMessage(result: SubmissionCommandResult): string {
    const text = (result.stderr || result.stdout || "Kubernetes admission check failed").trim();
    try {
        const payload = JSON.parse(text);
        if (payload && typeof payload.message === "string") {
            return payload.message;
        }
    } catch {
        // kubectl commonly adds human-readable prefixes around API errors.
    }
    return text;
}

function isNotFound(result: SubmissionCommandResult): boolean {
    const message = apiMessage(result).toLowerCase();
    return message.includes("notfound") || message.includes("not found");
}

function definiteSchemaFailure(message: string): boolean {
    const lower = message.toLowerCase();
    if (lower.includes("validatingadmissionpolicy")) {
        return false;
    }
    return lower.includes("strict decoding error")
        || lower.includes("cannot unmarshal")
        || (
            lower.includes("spec.")
            && [
                "invalid value",
                "required value",
                "unsupported value",
                "unknown field",
                "must be",
            ].some(marker => lower.includes(marker))
        );
}

function classifyFailure(message: string): SubmissionPreflightClassification {
    const lower = message.toLowerCase();
    if (
        lower.includes("impossible:")
        || (
            lower.includes("permanently sealed")
            && lower.includes("delete the resource")
        )
    ) {
        return "recreate-required";
    }
    if (
        lower.includes("gated changes detected")
        || (lower.includes("approvalgate") && lower.includes("approve"))
    ) {
        return "approval-required";
    }
    if (definiteSchemaFailure(message)) {
        return "invalid";
    }
    return "warning";
}

function issue(
    resource: SubmissionPreflightResource,
    classification: SubmissionPreflightClassification,
    message: string,
    source: SubmissionPreflightIssue["source"],
): SubmissionPreflightIssue {
    const {kind, metadata} = resource.manifest;
    const name = metadata.name;
    const plural = KIND_TO_PLURAL[kind];
    const blocking = BLOCKING_CLASSIFICATIONS.has(classification);
    return {
        kind,
        name,
        ...(plural ? {plural} : {}),
        classification,
        blocking,
        message,
        source,
        ...(plural ? {resourceId: `resource:${plural}:${name}`} : {}),
        ...(classification === "recreate-required" && plural
            ? {resetTargetId: `reset:${plural}:${name}`}
            : {}),
    };
}

function candidateForAdmission(
    resource: SubmissionPreflightResource,
    existing: Record<string, any> | undefined,
    namespace: string | undefined,
): SubmissionPreflightResource["manifest"] {
    const desired = resource.manifest;
    const existingMetadata = {...(existing?.metadata ?? {})};
    delete existingMetadata.managedFields;
    const desiredMetadata: Record<string, unknown> = {
        ...(desired.metadata ?? {}),
    };
    for (const key of [
        "creationTimestamp",
        "deletionGracePeriodSeconds",
        "deletionTimestamp",
        "generation",
        "managedFields",
        "resourceVersion",
        "selfLink",
        "uid",
    ]) {
        delete desiredMetadata[key];
    }
    const metadata = {
        ...existingMetadata,
        ...desiredMetadata,
        labels: {
            ...(existingMetadata.labels ?? {}),
            ...((desiredMetadata.labels as Record<string, string> | undefined) ?? {}),
        },
        annotations: {
            ...(existingMetadata.annotations ?? {}),
            ...((desiredMetadata.annotations as Record<string, string> | undefined) ?? {}),
        },
        ...(namespace ? {namespace} : {}),
    };
    if (Object.keys(metadata.annotations).length === 0) {
        delete metadata.annotations;
    }
    return {
        ...desired,
        metadata,
    };
}

function projectionIssues(
    resource: SubmissionPreflightResource,
    existing: Record<string, any>,
): SubmissionPreflightIssue[] {
    if (!resource.policyResource || existing.status?.phase === "Created") {
        return [];
    }
    const comparison = dryRunResourcePolicy(
        {
            kind: resource.policyResource.kind,
            name: resource.policyResource.name,
            parameters: existing.spec ?? {},
        },
        resource.policyResource,
    );
    return comparison.changes
        .filter(change => change.result !== "allowed")
        .map(change => issue(
            resource,
            change.result === "blocked"
                ? "recreate-required"
                : "approval-required",
            change.message,
            "projection-policy",
        ));
}

export async function preflightSubmissionResources(
    resources: SubmissionPreflightResource[],
    options: {
        namespace?: string;
        run?: SubmissionCommandRunner;
    } = {},
): Promise<SubmissionPreflightReport> {
    const run = options.run ?? defaultRunner;
    const issues: SubmissionPreflightIssue[] = [];

    for (const resource of resources) {
        const manifestText = stringify(resource.manifest);
        const namespaceArgs = options.namespace
            ? ["--namespace", options.namespace]
            : [];
        const getResult = run(
            ["get", "-f", "-", ...namespaceArgs, "-o", "json"],
            manifestText,
        );
        let existing: Record<string, any> | undefined;
        if (getResult.status === 0) {
            try {
                existing = JSON.parse(getResult.stdout);
            } catch {
                issues.push(issue(
                    resource,
                    "warning",
                    "Kubernetes returned an unreadable resource during admission preflight.",
                    "preflight",
                ));
                continue;
            }
        } else if (!isNotFound(getResult)) {
            issues.push(issue(
                resource,
                "warning",
                apiMessage(getResult),
                "kubernetes",
            ));
            continue;
        }

        const candidate = candidateForAdmission(resource, existing, options.namespace);
        const admissionResult = run(
            [
                existing ? "replace" : "create",
                "--dry-run=server",
                "-f",
                "-",
                ...namespaceArgs,
                "-o",
                "json",
            ],
            stringify(candidate),
        );
        if (admissionResult.status === 0) {
            continue;
        }

        const message = apiMessage(admissionResult);
        const classification = classifyFailure(message);
        if (classification === "warning" && existing) {
            const projected = projectionIssues(resource, existing);
            if (projected.length > 0) {
                issues.push(...projected);
                continue;
            }
        }
        issues.push(issue(resource, classification, message, "kubernetes"));
    }

    return {
        formatVersion: 1,
        allowed: !issues.some(item => item.blocking),
        checkedResources: resources.length,
        issues,
    };
}

export async function preflightSubmissionBundle(
    bundleDir: string,
    options: {namespace?: string; run?: SubmissionCommandRunner} = {},
): Promise<SubmissionPreflightReport> {
    const resources = JSON.parse(await fs.readFile(
        path.join(bundleDir, "submissionPreflightResources.json"),
        "utf8",
    )) as SubmissionPreflightResource[];
    return preflightSubmissionResources(resources, options);
}

export async function main(args: string[] = process.argv.slice(2)): Promise<void> {
    let bundleDir: string | undefined;
    let outputFile: string | undefined;
    let namespace: string | undefined;
    for (let index = 0; index < args.length; index++) {
        if (args[index] === "--bundle-dir") {
            bundleDir = args[++index];
        } else if (args[index] === "--output") {
            outputFile = args[++index];
        } else if (args[index] === "--namespace") {
            namespace = args[++index];
        } else {
            throw new Error(`Unknown submission preflight argument: ${args[index]}`);
        }
    }
    if (!bundleDir) {
        throw new Error("--bundle-dir is required");
    }
    const report = await preflightSubmissionBundle(bundleDir, {namespace});
    const output = `${JSON.stringify(report, null, 2)}\n`;
    if (outputFile) {
        await fs.writeFile(outputFile, output);
    } else {
        process.stdout.write(output);
    }
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch(error => {
        console.error(error);
        process.exit(1);
    });
}
