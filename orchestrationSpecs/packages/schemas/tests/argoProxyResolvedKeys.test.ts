import {
    ARGO_PROXY_CR_OMITTED_KEYS,
    ARGO_PROXY_OPTIONS,
    ARGO_PROXY_RESOLVED_ONLY_KEYS,
    ARGO_PROXY_WORKFLOW_OPTION_KEYS,
} from "../src/argoSchemas";
import {USER_PROXY_OPTIONS, getZodKeys} from "../src/userSchemas";

/**
 * Locks in the structural definition of the proxy resolved-only key set.
 *
 * ARGO_PROXY_OPTIONS = USER_PROXY_OPTIONS.extend(PROXY_RESOLVED_FIELDS), so the
 * resolved-only fields are exactly the keys ARGO adds over the user schema. The
 * CaptureProxy CRD is projected from USER_PROXY_OPTIONS, so that same delta is the
 * set of top-level keys the CRD does not define and that must be stripped from the
 * custom resource (the mTLS leak fixed in #3071). Deriving the list instead of
 * hand-maintaining it means a newly-added resolved field cannot silently drift.
 */
describe("ARGO_PROXY_RESOLVED_ONLY_KEYS", () => {
    it("equals keys(ARGO_PROXY_OPTIONS) - keys(USER_PROXY_OPTIONS)", () => {
        const userKeys = new Set(getZodKeys(USER_PROXY_OPTIONS) as string[]);
        const argoOnly = (getZodKeys(ARGO_PROXY_OPTIONS) as string[])
            .filter(key => !userKeys.has(key));

        expect([...ARGO_PROXY_RESOLVED_ONLY_KEYS].sort()).toEqual([...argoOnly].sort());
    });

    it("contributes only resolved fields to the CR omit set, deduped with workflow keys", () => {
        const expected = [
            ...new Set([...ARGO_PROXY_WORKFLOW_OPTION_KEYS, ...ARGO_PROXY_RESOLVED_ONLY_KEYS]),
        ];
        // No duplicates survive into the omit list applied by makeCaptureProxyManifest.
        expect(ARGO_PROXY_CR_OMITTED_KEYS).toEqual(expected);
        expect(ARGO_PROXY_CR_OMITTED_KEYS.length).toBe(new Set(ARGO_PROXY_CR_OMITTED_KEYS).size);
    });
});
