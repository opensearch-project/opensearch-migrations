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
        const inner = schema._def.in;
        return unwrapSchema(inner as any, constructorNames);
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
                actualType = actualType._def.in;
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