/**
 * Spec loader — reads a YAML spec file, validates it against
 * ScenarioSpecSchema, and resolves the baseConfig path relative to the
 * spec file's directory.
 *
 * The loader is intentionally small; it does not touch the cluster and
 * does not read the referenced baseConfig. Resolving that is the
 * executor's job.
 */

import * as fs from "node:fs";
import * as path from "node:path";
import { parse as parseYaml } from "yaml";

import { ScenarioSpec, ScenarioSpecSchema } from "./types";

export class SpecLoadError extends Error {
    constructor(
        message: string,
        public readonly specPath: string,
        public readonly cause?: unknown,
    ) {
        super(message);
        this.name = "SpecLoadError";
    }
}

export interface LoadedSpec {
    spec: ScenarioSpec;
    /** Directory containing the spec file — used to resolve baseConfig. */
    specDir: string;
    /** Absolute path to the resolved baseline config file. */
    resolvedBaseConfigPath: string;
}

/**
 * Parse a spec string without touching the filesystem. Useful for tests
 * and any caller that already has the YAML in memory.
 *
 * `specDirForResolve` is used only to resolve the baseConfig path; pass
 * `null` if the caller does not care.
 */
export function parseScenarioSpec(
    yamlText: string,
    specDirForResolve: string | null,
): LoadedSpec {
    let raw: unknown;
    try {
        raw = parseYaml(yamlText);
    } catch (e) {
        throw new SpecLoadError("spec is not valid YAML", "<in-memory>", e);
    }

    const parsed = ScenarioSpecSchema.safeParse(raw);
    if (!parsed.success) {
        throw new SpecLoadError(
            `spec failed schema validation:\n${formatZodIssues(parsed.error.issues)}`,
            "<in-memory>",
            parsed.error,
        );
    }

    const spec = parsed.data;
    const specDir = specDirForResolve ?? process.cwd();
    const resolvedBaseConfigPath = path.resolve(specDir, spec.baseConfig);

    return { spec, specDir, resolvedBaseConfigPath };
}

/**
 * Load a spec from disk. Throws `SpecLoadError` for missing files or
 * schema failures; both cases include the spec path in the message.
 */
export function loadScenarioSpec(specPath: string): LoadedSpec {
    const abs = path.resolve(specPath);
    let yamlText: string;
    try {
        yamlText = fs.readFileSync(abs, "utf8");
    } catch (e) {
        throw new SpecLoadError(`cannot read spec file`, abs, e);
    }

    const specDir = path.dirname(abs);
    try {
        return parseScenarioSpec(yamlText, specDir);
    } catch (e) {
        if (e instanceof SpecLoadError) {
            // Re-throw with the real path attached.
            throw new SpecLoadError(e.message, abs, e.cause);
        }
        throw e;
    }
}

type ZodIssue = {
    path: readonly PropertyKey[];
    message: string;
};

function formatZodIssues(issues: readonly ZodIssue[]): string {
    return issues
        .map((issue) => `  - ${issue.path.map(String).join(".") || "<root>"}: ${issue.message}`)
        .join("\n");
}
