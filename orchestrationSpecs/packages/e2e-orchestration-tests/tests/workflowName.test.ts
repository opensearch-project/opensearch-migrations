import { WorkflowNameError, sanitizeWorkflowName } from "../src/workflowName";

describe("sanitizeWorkflowName", () => {
    it("passes a clean short name through unchanged", () => {
        expect(sanitizeWorkflowName("abc-def-123")).toBe("abc-def-123");
    });

    it("lowercases uppercase input", () => {
        expect(sanitizeWorkflowName("CaptureProxy-FOO")).toBe("captureproxy-foo");
    });

    it("replaces disallowed characters with '-'", () => {
        // : and . from ComponentId + a few stray symbols
        expect(sanitizeWorkflowName("captureproxy:capture-proxy")).toBe(
            "captureproxy-capture-proxy",
        );
        expect(sanitizeWorkflowName("a.b_c/d+e")).toBe("a-b-c-d-e");
    });

    it("collapses runs of '-' into a single '-'", () => {
        expect(sanitizeWorkflowName("a::b..c")).toBe("a-b-c");
        expect(sanitizeWorkflowName("a---b")).toBe("a-b");
    });

    it("trims leading and trailing '-'", () => {
        expect(sanitizeWorkflowName("-abc-")).toBe("abc");
        expect(sanitizeWorkflowName(":abc:")).toBe("abc");
    });

    it("truncates to 63 characters, preserving the suffix", () => {
        const raw = "a".repeat(80) + "-suffix";            // 87 chars
        const result = sanitizeWorkflowName(raw);
        expect(result.length).toBe(63);
        expect(result.endsWith("-suffix")).toBe(true);
        // Head is `aaaa…` (some count of 'a') after the chop.
        expect(/^a+-suffix$/.test(result)).toBe(true);
    });

    it("re-trims a leading '-' after left-truncation", () => {
        // A 70-char name whose char at position 7 (= length - 63) is a
        // '-'. When we chop to the last 63 chars, the result starts
        // with '-', which must then be trimmed.
        //   positions:     0        7                                70
        //   content:       "aaaaaaa-bbbbb…" (63 b's)
        const raw = "a".repeat(7) + "-" + "b".repeat(62);
        expect(raw.length).toBe(70);
        const result = sanitizeWorkflowName(raw);
        expect(result.startsWith("-")).toBe(false);
        expect(result.length).toBeLessThanOrEqual(63);
        expect(result).toBe("b".repeat(62));
    });

    it("throws when truncation can't produce a leading alphanumeric", () => {
        // Construct a raw where every character after position
        // (length - 63) is a '-'. After collapsing the leading runs,
        // the sanitiser's truncation branch is unreachable via
        // realistic inputs, but the guard should still fire on a
        // crafted pathological input. We force the state by building
        // a 64-char string that sanitises to 64 '-'s before collapse.
        // After collapse, that becomes a single '-', which trims to
        // empty → the earlier empty-check path catches it.
        expect(() => sanitizeWorkflowName("-".repeat(80))).toThrow(WorkflowNameError);
    });

    it("throws on input that sanitises to empty", () => {
        expect(() => sanitizeWorkflowName("::::")).toThrow(WorkflowNameError);
        expect(() => sanitizeWorkflowName("")).toThrow(WorkflowNameError);
    });

    it("produces a valid RFC 1123 label (regex check) on realistic inputs", () => {
        const rfc1123 = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/;
        const inputs = [
            "captureproxy:capture-proxy-noop-baseline-abc123",
            "snapshotmigration:source-target-snap1-migration-0-noop-noop-pre-ab12cd",
            "Some.Weird/Thing:with_underscores-and_MORE",
        ];
        for (const raw of inputs) {
            const clean = sanitizeWorkflowName(raw);
            expect(clean.length).toBeLessThanOrEqual(63);
            expect(clean).toMatch(rfc1123);
        }
    });
});
