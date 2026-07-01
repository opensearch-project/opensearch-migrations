/**
 * Sanitize label parts into a valid RFC-1123 subdomain segment used for
 * Kubernetes CRD names. This is the single TypeScript source of truth for
 * CRD-name construction: the config transformer stamps the resolved name onto
 * each migration item, and the initializer reuses it when creating CRDs and
 * keying the resourceUid lookup map.
 *
 * Algorithm (kept in lock-step with the jq `crdname()` helper used at
 * workflow-submit time in migrationInitializer.ts):
 *   1. lowercase
 *   2. replace any run of chars not in [a-z0-9.-] with a single "-"
 *   3. collapse runs of "-" to a single "-"
 *   4. strip leading "-" or "."
 *   5. strip trailing "-" or "."
 */
export function crdName(...labels: string[]): string {
    const raw = labels.join('-').toLowerCase();
    const chars: string[] = [];
    for (const ch of raw) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch === '.') {
            chars.push(ch);
        } else if (chars.length > 0 && chars[chars.length - 1] !== '-') {
            chars.push('-');
        }
    }
    let start = 0;
    while (start < chars.length && (chars[start] === '-' || chars[start] === '.')) start++;
    let end = chars.length - 1;
    while (end >= start && (chars[end] === '-' || chars[end] === '.')) end--;
    return chars.slice(start, end + 1).join('');
}
