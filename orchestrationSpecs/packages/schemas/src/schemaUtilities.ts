/**
 * Default resource configurations for migration workflow components.
 * 
 * These defaults are recommended starting points and can be overridden in user configuration.
 * All values use Kubernetes resource quantity format.
 */

export const DEFAULT_RESOURCES = {
    REPLAYER: {
        limits: {
            cpu: "2000m",
            memory: "4000Mi",
        },
        requests: {
            cpu: "2000m",
            memory: "4000Mi",
        }
    },

    MIGRATION_CONSOLE_CLI: {
        limits: {
            cpu: "1000m",
            memory: "2000Mi",
        },
        requests: {
            cpu: "500m",
            memory: "2000Mi",
        }
    },

    RFS: {
        limits: {
            cpu: "2000m",
            memory: "4000Mi",
        },
        requests: {
            cpu: "1000m",
            memory: "4000Mi",
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
