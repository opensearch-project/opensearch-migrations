import { z } from "zod";
import { unwrapSchema, getDescription, fullUnwrapType } from "../src/schemaUtilities";

describe("unwrapSchema through ZodPipe", () => {
    test("z.preprocess(fn, innerSchema) unwraps to the inner schema, not the transform", () => {
        // This mirrors the RESOURCE_REQUIREMENTS pattern in userSchemas.ts where
        // z.preprocess is used to deep-merge defaults into a structured schema.
        // Regression: before the fix, unwrapSchema returned the ZodTransform
        // (preprocess fn), which caused the sample-config generator to render
        // "resources": "",  # unknown  instead of the nested limits/requests object.
        const INNER = z.object({
            limits: z.object({ cpu: z.string(), memory: z.string() }).optional(),
            requests: z.object({ cpu: z.string(), memory: z.string() }).optional(),
        });
        const PREPROCESSED = z.preprocess((v) => v, INNER);

        const unwrapped = unwrapSchema(PREPROCESSED);
        expect(unwrapped).toBeInstanceOf(z.ZodObject);
    });

    test("schema.transform(fn) unwraps to the source schema, not the transform", () => {
        const SRC = z.string();
        const TRANSFORMED = SRC.transform((s) => s.toUpperCase());

        const unwrapped = unwrapSchema(TRANSFORMED);
        expect(unwrapped).toBeInstanceOf(z.ZodString);
    });

    test("schema.pipe(otherSchema) unwraps to the source schema", () => {
        const SRC = z.string();
        const PIPED = SRC.pipe(z.string().min(1));

        const unwrapped = unwrapSchema(PIPED);
        expect(unwrapped).toBeInstanceOf(z.ZodString);
    });
});

describe("getDescription walks wrapper chains", () => {
    // Regression: .describe() can be attached anywhere in the construction
    // chain. fullUnwrapType() only returns the innermost concrete schema, so
    // the renderer was reading .description off a ZodString/ZodNumber that
    // never carried the description, producing sample-config entries with no
    // "# description" comment. getDescription() walks the whole chain.

    test("description on outer ZodOptional (.default().optional().describe())", () => {
        // Pattern used throughout userSchemas.ts for jvmArgs/podReplicas/etc.
        const FIELD = z.string().default("").optional().describe("JVM args to append");
        expect(getDescription(FIELD)).toBe("JVM args to append");
    });

    test("description on ZodDefault (.optional().default().describe())", () => {
        const FIELD = z.string().optional().default("").describe("described default");
        expect(getDescription(FIELD)).toBe("described default");
    });

    test("description on innermost concrete type (.describe().default().optional())", () => {
        const FIELD = z.string().describe("innermost").default("").optional();
        expect(getDescription(FIELD)).toBe("innermost");
    });

    test("description on ZodPipe from z.preprocess().describe().default()", () => {
        // Pattern used for the `resources` fields in userSchemas.ts where
        // the describe sits between the preprocess pipe and the default.
        const INNER = z.object({ limits: z.object({ cpu: z.string() }).optional() });
        const FIELD = z.preprocess((v) => v ?? {}, INNER)
            .describe("resource overrides")
            .default({ limits: { cpu: "1" } });
        expect(getDescription(FIELD)).toBe("resource overrides");
    });

    test("returns undefined when no description is attached anywhere", () => {
        const FIELD = z.string().default("").optional();
        expect(getDescription(FIELD)).toBeUndefined();
    });

    test("returns the OUTERMOST description when multiple are attached", () => {
        // If the author describes at several layers, the outermost wins --
        // that's the most recently-applied / most specific override.
        const FIELD = z.string().describe("inner").default("").describe("outer");
        expect(getDescription(FIELD)).toBe("outer");
    });
});

describe("fullUnwrapType through ZodPipe", () => {
    test("z.preprocess(fn, innerSchema) wrapped in optional unwraps to the inner schema, not the transform", () => {
        // Same latent bug as unwrapSchema/getDescription: z.preprocess puts the
        // ZodTransform on _def.in, so naively picking .in would hand back the
        // preprocess fn. fullUnwrapType must walk to _def.out in that case.
        const INNER = z.object({
            limits: z.object({ cpu: z.string() }).optional(),
        });
        const FIELD = z.preprocess((v) => v ?? {}, INNER).optional();

        const unwrapped = fullUnwrapType(FIELD);
        expect(unwrapped).toBeInstanceOf(z.ZodObject);
    });

    test("schema.transform(fn) wrapped in optional unwraps to the source schema", () => {
        // For .transform() the source schema is on .in and the ZodTransform is
        // on .out -- the existing .in branch is correct here. This test pins
        // that the preprocess fix did not regress the transform direction.
        const FIELD = z.string().transform((s) => s.toUpperCase()).optional();

        const unwrapped = fullUnwrapType(FIELD);
        expect(unwrapped).toBeInstanceOf(z.ZodString);
    });
});
