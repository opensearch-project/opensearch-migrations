/**
 * Workflow-name sanitisation.
 *
 * Argo workflow names are RFC 1123 labels: max 63 characters; may
 * contain lowercase alphanumeric characters and `-`; must start and
 * end with an alphanumeric character. Names outside that contract are
 * rejected at submission time by the API server.
 *
 * Our raw composed names look like
 *   `${caseName}-${runName}-${suffix}`
 * where `caseName` is derived from a `ComponentId` that permits `:`,
 * `.`, and `_`. Those aren't legal in RFC 1123 labels, and the total
 * length can exceed 63 characters for CRDs with long derived names.
 *
 * The sanitiser:
 *   - lowercases the name,
 *   - replaces any character outside `[a-z0-9-]` with `-`,
 *   - collapses runs of `-`,
 *   - trims leading/trailing `-`,
 *   - if still over 63 characters, truncates from the **left**,
 *     preserving the trailing suffix (uniqueness tail). Truncation
 *     re-trims leading `-` in case a head chop landed on one.
 *
 * If the input is empty after sanitisation (pathological — e.g. a
 * caller passed `"::"`), this throws. That's a programmer error, not
 * a runtime condition we want to silently paper over.
 */

const MAX_LEN = 63;

export class WorkflowNameError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "WorkflowNameError";
    }
}

export function sanitizeWorkflowName(raw: string): string {
    let s = raw
        .toLowerCase()
        .replace(/[^a-z0-9-]/g, "-")
        .replace(/-+/g, "-")
        .replace(/^-+|-+$/g, "");

    if (s.length === 0) {
        throw new WorkflowNameError(
            `workflow name '${raw}' sanitises to an empty string`,
        );
    }

    if (s.length > MAX_LEN) {
        // Chop from the left so the unique suffix survives.
        s = s.slice(s.length - MAX_LEN);
        // The head chop may have landed mid-label; re-trim.
        s = s.replace(/^-+/, "");
        // If nothing alphanumeric remains (can't happen in practice
        // given our composition, but guard against future callers),
        // surface as a loud error rather than return a malformed name.
        if (s.length === 0 || !/^[a-z0-9]/.test(s)) {
            throw new WorkflowNameError(
                `workflow name '${raw}' cannot be truncated to ${MAX_LEN} characters while keeping a leading alphanumeric`,
            );
        }
    }

    return s;
}
