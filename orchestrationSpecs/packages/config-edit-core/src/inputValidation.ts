import {z} from "zod";

export class InputValidationElement {
    constructor(
        public readonly path: PropertyKey[],
        public readonly message: string,
    ) {}
}

export class InputValidationError extends Error {
    constructor(
        public readonly errors: InputValidationElement[],
    ) {
        super();
        this.name = "InputValidationError";
    }

    get message(): string {
        return `Found ${this.errors.length} errors: ${formatInputValidationError(this, {singleLine: true})}`;
    }
}

export function formatInputValidationError(
    error: InputValidationError,
    options?: {singleLine?: boolean},
): string {
    const singleLine = options?.singleLine ?? false;
    return error.errors
        .map(item => [item.message, item.path.map(part => part.toString()).join(".")])
        .map(([message, path]) => singleLine
            ? `${message} at: ${path}`
            : `${message}... at:\n  ${path}`)
        .join(singleLine ? "; " : "\n");
}

export function stripComments<T>(obj: T): T {
    if (obj === null || typeof obj !== "object") {
        return obj;
    }
    if (Array.isArray(obj)) {
        return obj.map(stripComments) as T;
    }
    const result: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(obj)) {
        if (key.startsWith("//") || key.startsWith("#")) {
            continue;
        }
        result[key] = stripComments(value);
    }
    return result as T;
}

function hasValueAtPath(config: unknown, path: PropertyKey[]): boolean {
    let value = config;
    for (const part of path) {
        if (typeof value !== "object" || value === null || !(part in value)) {
            return false;
        }
        value = (value as any)[part];
    }
    return value !== undefined;
}

function valueAtPath(config: unknown, path: PropertyKey[]): unknown {
    let value = config;
    for (const part of path) {
        if (typeof value !== "object" || value === null || !(part in value)) {
            return undefined;
        }
        value = (value as any)[part];
    }
    return value;
}

export function snapshotInfoCollectionForVersion(
    version: unknown,
): "snapshots" | "backups" {
    return typeof version === "string" && version.startsWith("SOLR ")
        ? "backups"
        : "snapshots";
}

function preferredSnapshotInfoBranch(
    issuePath: PropertyKey[],
    candidates: z.core.$ZodIssue[][],
    config: unknown,
): z.core.$ZodIssue[] | undefined {
    const snapshotInfoIndex = issuePath.length - 1;
    if (issuePath[snapshotInfoIndex] !== "snapshotInfo") {
        return undefined;
    }
    const version = (
        issuePath.length === 3
        && issuePath[0] === "sourceClusters"
    )
        ? valueAtPath(
            config,
            ["sourceClusters", issuePath[1], "version"],
        )
        : issuePath.length === 1
            ? valueAtPath(config, ["version"])
            : undefined;
    if (version === undefined) {
        return undefined;
    }
    const itemCollection = snapshotInfoCollectionForVersion(version);
    return candidates.find(branch => branch.some(candidate =>
        candidate.path.length > issuePath.length
        && candidate.path[issuePath.length] === itemCollection
    ));
}

export function actionableZodIssues(
    issues: z.core.$ZodIssue[],
    config: unknown,
): z.core.$ZodIssue[] {
    return issues.flatMap(issue => {
        if (issue.code === "invalid_key") {
            const nested = (issue as any).issues as z.core.$ZodIssue[] | undefined;
            if (nested?.length) {
                return actionableZodIssues(
                    nested.map(child => ({
                        ...child,
                        path: [...issue.path, ...child.path],
                    })),
                    config,
                );
            }
        }
        if (issue.code !== "invalid_union") {
            return [issue];
        }
        if (issue.message !== "Invalid input") {
            return [issue];
        }
        const branches = ((issue as any).errors ?? []) as z.core.$ZodIssue[][];
        const candidates = branches.map(branch => actionableZodIssues(
            branch.map(child => ({
                ...child,
                path: [...issue.path, ...child.path],
            })),
            config,
        ));
        const preferredSnapshotBranch = preferredSnapshotInfoBranch(
            issue.path,
            candidates,
            config,
        );
        if (preferredSnapshotBranch) {
            return preferredSnapshotBranch;
        }
        const ranked = candidates
            .map(branch => ({
                branch,
                presentValues: branch.filter(candidate =>
                    hasValueAtPath(config, candidate.path)).length,
            }))
            .sort((left, right) =>
                right.presentValues - left.presentValues
                || left.branch.length - right.branch.length);
        if (
            ranked[0]?.presentValues
            && ranked[0].presentValues > (ranked[1]?.presentValues ?? -1)
        ) {
            return ranked[0].branch;
        }
        const sharedIssues = (candidates[0] ?? []).filter(candidate =>
            candidates.every(branch => branch.some(other =>
                other.code === candidate.code
                && other.message === candidate.message
                && JSON.stringify(other.path) === JSON.stringify(candidate.path)
            )));
        return sharedIssues.length
            ? sharedIssues
            : [issue];
    });
}

export function parseWithValidation<TSchema extends z.ZodType>(
    schema: TSchema,
    data: unknown,
): z.infer<TSchema> {
    const strippedData = stripComments(data);
    const result = schema.safeParse(strippedData);
    if (!result.success) {
        throw new InputValidationError(actionableZodIssues(
            result.error.issues,
            strippedData,
        ).map(issue =>
            new InputValidationElement(issue.path, issue.message)
        ));
    }
    return result.data;
}
