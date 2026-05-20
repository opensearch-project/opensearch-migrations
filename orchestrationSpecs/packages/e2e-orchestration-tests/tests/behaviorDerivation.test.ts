import { deriveBehavior } from "../src/behaviorDerivation";
import { ComponentId, ObservedComponent } from "../src/types";

const C = "captureproxy:capture-proxy" as ComponentId;

function obs(overrides: Partial<ObservedComponent>): ObservedComponent {
    return {
        componentId: C,
        phase: "Ready",
        ...overrides,
    };
}

describe("deriveBehavior", () => {
    it("returns 'ran' when there is no prior observation and the current phase is terminal", () => {
        expect(deriveBehavior({ prev: null, curr: obs({ phase: "Ready" }) })).toBe("ran");
        expect(deriveBehavior({ prev: null, curr: obs({ phase: "Completed" }) })).toBe("ran");
        expect(deriveBehavior({ prev: null, curr: obs({ phase: "Skipped" }) })).toBe("ran");
    });

    it("returns 'skipped' when uid+checksum+generation are unchanged and phase is terminal", () => {
        const prev = obs({ uid: "u1", configChecksum: "c1", generation: 3 });
        const curr = obs({ phase: "Completed", uid: "u1", configChecksum: "c1", generation: 3 });
        expect(deriveBehavior({ prev, curr })).toBe("skipped");
    });

    it("returns 'reran' when checksum changed", () => {
        const prev = obs({ uid: "u1", configChecksum: "c1", generation: 3 });
        const curr = obs({ uid: "u1", configChecksum: "c2", generation: 3 });
        expect(deriveBehavior({ prev, curr })).toBe("reran");
    });

    it("returns 'reran' when uid changed (resource was recreated)", () => {
        const prev = obs({ uid: "u1", configChecksum: "c1" });
        const curr = obs({ uid: "u2", configChecksum: "c1" });
        expect(deriveBehavior({ prev, curr })).toBe("reran");
    });

    it("returns 'reran' when generation changed", () => {
        const prev = obs({ uid: "u1", generation: 3 });
        const curr = obs({ uid: "u1", generation: 4 });
        expect(deriveBehavior({ prev, curr })).toBe("reran");
    });

    it("returns 'skipped' when only missing-on-both fields stay missing", () => {
        // No uid/checksum/generation on either side means no detectable
        // change — treat as skipped.
        const prev = obs({});
        const curr = obs({});
        expect(deriveBehavior({ prev, curr })).toBe("skipped");
    });

    it("maps Blocked phase to 'blocked'", () => {
        expect(deriveBehavior({ prev: null, curr: obs({ phase: "Blocked" }) })).toBe(
            "blocked",
        );
    });

    it("maps Suspended/Paused to 'gated'", () => {
        expect(deriveBehavior({ prev: null, curr: obs({ phase: "Suspended" }) })).toBe(
            "gated",
        );
        expect(deriveBehavior({ prev: null, curr: obs({ phase: "Paused" }) })).toBe(
            "gated",
        );
    });

    it("maps Initialized/Pending/missing to 'unstarted'", () => {
        expect(deriveBehavior({ prev: null, curr: obs({ phase: "Initialized" }) })).toBe(
            "unstarted",
        );
        expect(deriveBehavior({ prev: null, curr: obs({ phase: "Pending" }) })).toBe(
            "unstarted",
        );
        expect(deriveBehavior({ prev: null, curr: obs({ phase: "" }) })).toBe(
            "unstarted",
        );
    });

    it("maps an unrecognised phase to 'unstarted' so assertLogic fails loudly", () => {
        expect(deriveBehavior({ prev: null, curr: obs({ phase: "Running" }) })).toBe(
            "unstarted",
        );
        expect(deriveBehavior({ prev: null, curr: obs({ phase: "Mystery" }) })).toBe(
            "unstarted",
        );
    });
});
