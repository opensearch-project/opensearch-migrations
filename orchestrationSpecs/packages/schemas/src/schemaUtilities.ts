/**
 * Default resource configurations for migration workflow components.
 *
 * These defaults are recommended starting points and can be overridden in user configuration.
 * All values use Kubernetes resource quantity format.
 */
import {z} from "zod";

const SIMPLE_CONSOLE_CLI_RESOURCES = {
    limits: {
        cpu: "500m",
        memory: "500Mi",
    },
    requests: {
        cpu: "500m",
        memory: "500Mi",
    }
};

// See https://kubernetes.io/docs/concepts/workloads/pods/pod-qos/#guaranteed for details
// about how these values effect eviction priorities of pods.
export const DEFAULT_RESOURCES = {
    PROXY: {
        limits: {
            cpu: "3500m",
            memory: "3500Mi",
        },
        requests: {
            cpu: "3500m",
            memory: "3500Mi",
        }
    },

    REPLAYER: {
        limits: {
            cpu: "3500m",
            memory: "3500Mi",
        },
        requests: {
            cpu: "3500m",
            memory: "3500Mi",
        }
    },

    SHELL_MIGRATION_CONSOLE_CLI: SIMPLE_CONSOLE_CLI_RESOURCES,

    PYTHON_MIGRATION_CONSOLE_CLI: SIMPLE_CONSOLE_CLI_RESOURCES,

    JAVA_MIGRATION_CONSOLE_CLI: {
        limits: {
            cpu: "500m",
            memory: "1800Mi",
        },
        requests: {
            cpu: "500m",
            memory: "1800Mi",
        }
    },

    RFS: {
        limits: {
            cpu: "3300m",
            memory: "7000Mi",
        },
        requests: {
            cpu: "3300m",
            memory: "7000Mi",
        }
    },

    // Strimzi entity-operator sidecars (topic-operator + user-operator), set per container.
    // These default to no resources at all, which makes them BestEffort and lets the
    // scheduler pack them onto an already-saturated node. On a busy 2-vCPU node the JVM
    // cold-start then loses the race against the operator's liveness probe and crash-loops,
    // and because the user-operator creates the KafkaUser SCRAM secret, that wedges the whole
    // migration. Reserve real CPU so the scheduler keeps them off saturated nodes.
    // requests==limits gives Guaranteed QoS. Sized to Strimzi's own operator footprint.
    ENTITY_OPERATOR: {
        limits: {
            cpu: "500m",
            memory: "512Mi",
        },
        requests: {
            cpu: "500m",
            memory: "512Mi",
        }
    },

    // Kafka broker/controller node-pool pods. requests==limits gives Guaranteed QoS.
    KAFKA_BROKER: {
        limits: {
            cpu: "1000m",
            memory: "2048Mi",
        },
        requests: {
            cpu: "1000m",
            memory: "2048Mi",
        }
    },
} as const;

export function parseK8sQuantity(qty: string): number {
  const match = /^([0-9.]+)([a-zA-Z]*)$/.exec(qty.trim());
  if (!match) throw new Error(`Invalid quantity: ${qty}`);

  const [, numStr, suffix] = match;
  const num = parseFloat(numStr);

  const binary: Record<string, number> = {
    Ki: 1024,
    Mi: 1024 ** 2,
    Gi: 1024 ** 3,
    Ti: 1024 ** 4,
    Pi: 1024 ** 5,
    Ei: 1024 ** 6,
  };
  const decimal: Record<string, number> = {
    n: 1e-9, u: 1e-6, m: 1e-3, // CPU-style milli units
    k: 1e3, M: 1e6, G: 1e9, T: 1e12, P: 1e15, E: 1e18,
  };

  if (suffix in binary) return num * binary[suffix];
  if (suffix in decimal) return num * decimal[suffix];
  if (suffix === "") return num;
  throw new Error(`Unsupported unit suffix: ${suffix}`);
}

export const ZOD_OPTIONAL_TYPES = ['ZodOptional', 'ZodNullable'];
export const ZOD_OPTIONAL_AND_DEFAULT_TYPES = ['ZodDefault', ...ZOD_OPTIONAL_TYPES];
export function unwrapSchema(schema: z.ZodTypeAny, constructorNames = ZOD_OPTIONAL_AND_DEFAULT_TYPES): z.ZodTypeAny {
    const constructorName = schema.constructor.name;

    if (constructorNames.indexOf(constructorName) != -1) {//} === 'ZodDefault' || constructorName === 'ZodOptional' || constructorName === 'ZodNullable') {
        const innerType = (schema as any)._def?.innerType;
        if (innerType) {
            return unwrapSchema(innerType as z.ZodTypeAny, constructorNames);
        }
    } else if (schema instanceof z.ZodPipe) {
        // ZodPipe is used for both z.preprocess(fn, schema) and schema.transform(fn) / schema.pipe().
        // - For z.preprocess: _def.in is the ZodTransform (the preprocess fn), _def.out is the real schema.
        // - For .transform / .pipe: _def.in is the source schema, _def.out is the ZodTransform.
        // In both cases we want the non-transform side so callers see the structured schema.
        const d = schema._def;
        const inner = d.in instanceof z.ZodTransform ? d.out : d.in;
        return unwrapSchema(inner as z.ZodTypeAny, constructorNames);
    }

    return schema;
}

export function fullUnwrapType<T extends z.ZodTypeAny>(schema: T) {
    if (schema instanceof z.ZodOptional) {
        const innerType = schema.unwrap();
        const hasDefault = innerType instanceof z.ZodDefault;

        // Unwrap to get the actual type
        let actualType = hasDefault ? (innerType as z.ZodDefault<any>).removeDefault() : innerType;

        while (true) {
            const def = (actualType as any).def || (actualType as any)._def;
            const typeName = def?.typeName || def?.type;
            if (actualType instanceof z.ZodPipe) {
                // Match unwrapSchema()/getDescription(): z.preprocess puts the ZodTransform
                // on _def.in, so unwrapping to .in would hand back the preprocess fn. Walk
                // to _def.out in that case; keep .in for .transform()/.pipe() where the
                // source schema is on .in and the ZodTransform is on .out.
                const d = actualType._def;
                actualType = d.in instanceof z.ZodTransform ? d.out : d.in;
            } else if (typeName === 'ZodEffects' || typeName === 'transform') {
                // For transforms, get the input schema
                if (typeof (actualType as any).innerType === 'function') {
                    actualType = (actualType as any).innerType();
                } else if (def.schema) {
                    actualType = def.schema;
                } else if (def.in) {
                    actualType = def.in;
                } else {
                    // Can't unwrap further, break
                    break;
                }
            } else if (actualType instanceof z.ZodOptional) {
                actualType = actualType.unwrap();
            } else if (actualType instanceof z.ZodDefault) {
                actualType = actualType.removeDefault();
            } else {
                // We've hit a concrete type
                break;
            }
        }
        return actualType;
    } else {
        return schema;
    }
}

/**
 * Walk through wrapper schemas (ZodOptional, ZodDefault, ZodNullable, ZodPipe) and
 * return the first non-empty `.description` found on any level.
 *
 * `.describe()` can be called anywhere in the construction chain, e.g.:
 *   z.string().default("").optional().describe("DOC")   -> description on ZodOptional
 *   z.string().optional().default("").describe("DOC")   -> description on ZodDefault
 *   z.string().describe("DOC").default("").optional()   -> description on ZodString
 *   z.preprocess(fn, inner).describe("DOC").default(d)  -> description on ZodPipe
 *
 * fullUnwrapType() only returns the innermost concrete type, so callers reading
 * `.description` off it miss descriptions attached to any outer wrapper.
 * Use this helper instead when you want the field's authored documentation.
 */
export function getDescription(schema: z.ZodTypeAny): string | undefined {
    let cur: z.ZodTypeAny = schema;
    while (true) {
        if (cur.description) return cur.description;
        if (cur instanceof z.ZodOptional) {
            cur = cur.unwrap() as z.ZodTypeAny;
        } else if (cur instanceof z.ZodDefault) {
            cur = cur.removeDefault() as z.ZodTypeAny;
        } else if (cur instanceof z.ZodNullable) {
            cur = cur.unwrap() as z.ZodTypeAny;
        } else if (cur instanceof z.ZodPipe) {
            // Match unwrapSchema()'s preprocess-aware behaviour: skip over the
            // ZodTransform side and continue walking through the real schema.
            const d = cur._def;
            cur = (d.in instanceof z.ZodTransform ? d.out : d.in) as z.ZodTypeAny;
        } else {
            return undefined;
        }
    }
}
