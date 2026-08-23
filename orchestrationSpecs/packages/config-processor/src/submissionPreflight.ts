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

export type SubmissionDeploymentReason =
    | "resource-missing"
    | "resource-not-ready"
    | "configuration-changed"
    | "checksum-only";

export interface SubmissionDeploymentAction {
    kind: string;
    name: string;
    plural?: string;
    action: "create" | "reconcile";
    reason: SubmissionDeploymentReason;
    message: string;
    resourceId?: string;
    currentConfigChecksum?: string;
    desiredConfigChecksum?: string;
}

export interface SubmissionPreflightReport {
    formatVersion: 1;
    allowed: boolean;
    checkedResources: number;
    issues: SubmissionPreflightIssue[];
    deploymentActions: SubmissionDeploymentAction[];
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
    desiredConfigChecksum?: string;
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

export interface SubmissionAdmissionClient {
    read(
        resource: SubmissionPreflightResource,
        namespace?: string,
    ): Promise<Record<string, any> | undefined>;
    dryRun(
        candidate: SubmissionPreflightResource["manifest"],
        existing: boolean,
    ): Promise<void>;
}

export interface SubmissionPreflightOptions {
    namespace?: string;
    run?: SubmissionCommandRunner;
    client?: SubmissionAdmissionClient;
    concurrency?: number;
}

export const DEFAULT_SUBMISSION_PREFLIGHT_CONCURRENCY = 8;

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

class SubmissionCommandError extends Error {
    constructor(readonly result: SubmissionCommandResult) {
        super(apiMessage(result));
    }
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

function errorMessage(error: unknown): string {
    if (error instanceof SubmissionCommandError) {
        return apiMessage(error.result);
    }
    if (error && typeof error === "object") {
        const candidate = error as {
            body?: unknown;
            message?: unknown;
            reason?: unknown;
        };
        const body = candidate.body;
        if (body && typeof body === "object") {
            const message = (body as {message?: unknown}).message;
            if (typeof message === "string" && message.trim()) {
                return message.trim();
            }
        }
        if (typeof body === "string" && body.trim()) {
            try {
                const parsed = JSON.parse(body);
                if (typeof parsed?.message === "string") {
                    return parsed.message.trim();
                }
            } catch {
                return body.trim();
            }
        }
        if (typeof candidate.message === "string" && candidate.message.trim()) {
            return candidate.message.trim();
        }
        if (typeof candidate.reason === "string" && candidate.reason.trim()) {
            return candidate.reason.trim();
        }
    }
    return String(error || "Kubernetes admission check failed").trim();
}

function isNotFound(result: SubmissionCommandResult): boolean {
    const message = apiMessage(result).toLowerCase();
    return message.includes("notfound") || message.includes("not found");
}

function commandAdmissionClient(
    run: SubmissionCommandRunner,
): SubmissionAdmissionClient {
    return {
        async read(resource, namespace) {
            const namespaceArgs = namespace
                ? ["--namespace", namespace]
                : [];
            const result = run(
                ["get", "-f", "-", ...namespaceArgs, "-o", "json"],
                stringify(resource.manifest),
            );
            if (result.status === 0) {
                try {
                    return JSON.parse(result.stdout);
                } catch {
                    throw new Error(
                        "Kubernetes returned an unreadable resource during admission preflight.",
                    );
                }
            }
            if (isNotFound(result)) {
                return undefined;
            }
            throw new SubmissionCommandError(result);
        },
        async dryRun(candidate, existing) {
            const namespace = candidate.metadata.namespace;
            const namespaceArgs = typeof namespace === "string" && namespace
                ? ["--namespace", namespace]
                : [];
            const result = run(
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
            if (result.status !== 0) {
                throw new SubmissionCommandError(result);
            }
        },
    };
}

async function kubernetesAdmissionClient(): Promise<SubmissionAdmissionClient> {
    const {createKubernetesSubmissionAdmissionClient} = await import(
        "./kubernetesSubmissionClient"
    );
    return createKubernetesSubmissionAdmissionClient();
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

function deploymentAction(
    resource: SubmissionPreflightResource,
    existing: Record<string, any> | undefined,
): SubmissionDeploymentAction | undefined {
    const desiredConfigChecksum = resource.desiredConfigChecksum;
    if (!desiredConfigChecksum) {
        return undefined;
    }

    const {kind, metadata} = resource.manifest;
    const name = metadata.name;
    const plural = KIND_TO_PLURAL[kind];
    const common = {
        kind,
        name,
        ...(plural ? {plural, resourceId: `resource:${plural}:${name}`} : {}),
        desiredConfigChecksum,
    };
    const withWorkflowNote = (message: string) => (
        kind === "CaptureProxy"
            ? `${message} The workflow will request proxy approval after deployment succeeds.`
            : message
    );
    if (!existing) {
        return {
            ...common,
            action: "create",
            reason: "resource-missing",
            message: withWorkflowNote(
                "The resource does not exist and will be created.",
            ),
        };
    }

    const currentConfigChecksum = typeof existing.status?.configChecksum === "string"
        ? existing.status.configChecksum
        : undefined;
    if (currentConfigChecksum === desiredConfigChecksum) {
        return undefined;
    }

    const phase = typeof existing.status?.phase === "string"
        ? existing.status.phase
        : undefined;
    const projectedChanges = resource.policyResource
        ? dryRunResourcePolicy(
            {
                kind: resource.policyResource.kind,
                name: resource.policyResource.name,
                parameters: existing.spec ?? {},
            },
            resource.policyResource,
        ).changes
        : [];
    if (phase && phase !== "Ready" && phase !== "Succeeded") {
        if (!currentConfigChecksum) {
            return {
                ...common,
                action: "reconcile",
                reason: "resource-not-ready",
                message: withWorkflowNote(
                    "Setup has not completed for this configuration. "
                    + "The workflow will retry setup; no reset is required.",
                ),
            };
        }
        const difference = projectedChanges.length > 0
            ? (
                `${projectedChanges.length} projected configuration `
                + `${projectedChanges.length === 1 ? "field has" : "fields have"} changed.`
            )
            : "No projected fields changed, so this is a checksum-only reconcile.";
        return {
            ...common,
            action: "reconcile",
            reason: "resource-not-ready",
            message: withWorkflowNote(
                `The resource is ${phase} and its generated checksum is not current; `
                + `the workflow will reconcile it. ${difference}`,
            ),
            ...(currentConfigChecksum ? {currentConfigChecksum} : {}),
        };
    }

    if (projectedChanges.length > 0) {
        return {
            ...common,
            action: "reconcile",
            reason: "configuration-changed",
            message: withWorkflowNote(
                `${projectedChanges.length} projected configuration `
                + `${projectedChanges.length === 1 ? "field has" : "fields have"} changed; `
                + "the workflow will reconcile this resource.",
            ),
            ...(currentConfigChecksum ? {currentConfigChecksum} : {}),
        };
    }

    return {
        ...common,
        action: "reconcile",
        reason: "checksum-only",
        message: withWorkflowNote(
            "The workflow will reconcile this resource because its generated "
            + "checksum changed, although no projected fields changed.",
        ),
        ...(currentConfigChecksum ? {currentConfigChecksum} : {}),
    };
}

async function preflightResource(
    resource: SubmissionPreflightResource,
    client: SubmissionAdmissionClient,
    namespace?: string,
): Promise<{
    issues: SubmissionPreflightIssue[];
    deploymentAction?: SubmissionDeploymentAction;
}> {
    let existing: Record<string, any> | undefined;
    try {
        existing = await client.read(resource, namespace);
    } catch (error) {
        return {
            issues: [issue(
                resource,
                "warning",
                errorMessage(error),
                "kubernetes",
            )],
        };
    }

    const candidate = candidateForAdmission(resource, existing, namespace);
    const plannedAction = deploymentAction(resource, existing);
    try {
        await client.dryRun(candidate, existing !== undefined);
        return {
            issues: [],
            ...(plannedAction ? {deploymentAction: plannedAction} : {}),
        };
    } catch (error) {
        const message = errorMessage(error);
        const classification = classifyFailure(message);
        if (classification === "warning" && existing) {
            const projected = projectionIssues(resource, existing);
            if (projected.length > 0) {
                return {
                    issues: projected,
                    ...(plannedAction ? {deploymentAction: plannedAction} : {}),
                };
            }
        }
        return {
            issues: [issue(resource, classification, message, "kubernetes")],
            ...(plannedAction ? {deploymentAction: plannedAction} : {}),
        };
    }
}

async function mapWithConcurrency<T, U>(
    items: T[],
    concurrency: number,
    operation: (item: T, index: number) => Promise<U>,
): Promise<U[]> {
    if (!Number.isInteger(concurrency) || concurrency < 1) {
        throw new Error("Submission preflight concurrency must be a positive integer");
    }
    const output = new Array<U>(items.length);
    let nextIndex = 0;
    const worker = async () => {
        while (nextIndex < items.length) {
            const index = nextIndex++;
            output[index] = await operation(items[index], index);
        }
    };
    await Promise.all(
        Array.from(
            {length: Math.min(concurrency, items.length)},
            worker,
        ),
    );
    return output;
}

export async function preflightSubmissionResources(
    resources: SubmissionPreflightResource[],
    options: SubmissionPreflightOptions = {},
): Promise<SubmissionPreflightReport> {
    if (resources.length === 0) {
        return {
            formatVersion: 1,
            allowed: true,
            checkedResources: 0,
            issues: [],
            deploymentActions: [],
        };
    }

    let client: SubmissionAdmissionClient;
    try {
        client = options.client
            ?? (
                options.run
                    ? commandAdmissionClient(options.run)
                    : await kubernetesAdmissionClient()
            );
    } catch (error) {
        const message = errorMessage(error);
        const issues = resources.map(resource => issue(
            resource,
            "warning",
            message,
            "preflight",
        ));
        return {
            formatVersion: 1,
            allowed: true,
            checkedResources: resources.length,
            issues,
            deploymentActions: [],
        };
    }

    const resourceResults = await mapWithConcurrency(
        resources,
        options.concurrency ?? DEFAULT_SUBMISSION_PREFLIGHT_CONCURRENCY,
        resource => preflightResource(resource, client, options.namespace),
    );
    const issues = resourceResults.flatMap(result => result.issues);
    const deploymentActions = resourceResults.flatMap(result =>
        result.deploymentAction ? [result.deploymentAction] : []
    );
    return {
        formatVersion: 1,
        allowed: !issues.some(item => item.blocking),
        checkedResources: resources.length,
        issues,
        deploymentActions,
    };
}

export async function preflightSubmissionBundle(
    bundleDir: string,
    options: SubmissionPreflightOptions = {},
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
    let concurrency: number | undefined;
    for (let index = 0; index < args.length; index++) {
        if (args[index] === "--bundle-dir") {
            bundleDir = args[++index];
        } else if (args[index] === "--output") {
            outputFile = args[++index];
        } else if (args[index] === "--namespace") {
            namespace = args[++index];
        } else if (args[index] === "--concurrency") {
            concurrency = Number(args[++index]);
            if (!Number.isInteger(concurrency) || concurrency < 1) {
                throw new Error("--concurrency requires a positive integer");
            }
        } else {
            throw new Error(`Unknown submission preflight argument: ${args[index]}`);
        }
    }
    if (!bundleDir) {
        throw new Error("--bundle-dir is required");
    }
    const report = await preflightSubmissionBundle(
        bundleDir,
        {namespace, concurrency},
    );
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
