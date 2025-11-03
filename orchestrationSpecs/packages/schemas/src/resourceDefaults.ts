/**
 * Default resource configurations for migration workflow components.
 * 
 * These defaults are recommended starting points and can be overridden in user configuration.
 * All values use Kubernetes resource quantity format.
 */

export const DEFAULT_RESOURCES = {
    CAPTURE_PROXY: {
        limits: {
            cpu: "2000m",
            memory: "4000Mi",
            "ephemeral-storage": "5Gi"
        },
        requests: {
            cpu: "2000m",
            memory: "4000Mi",
            "ephemeral-storage": "5Gi"
        }
    },

    REPLAYER: {
        limits: {
            cpu: "2000m",
            memory: "4000Mi",
            "ephemeral-storage": "5Gi"
        },
        requests: {
            cpu: "2000m",
            memory: "4000Mi",
            "ephemeral-storage": "5Gi"
        }
    },

    MIGRATION_CONSOLE: {
        limits: {
            cpu: "2000m",
            memory: "4000Mi",
            "ephemeral-storage": "20Gi"
        },
        requests: {
            cpu: "500m",
            memory: "4000Mi",
            "ephemeral-storage": "20Gi"
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
            "ephemeral-storage": "200Gi"
        },
        requests: {
            cpu: "1000m",
            memory: "4000Mi",
            "ephemeral-storage": "200Gi"
        }
    },

    OTEL_COLLECTOR: {
        limits: {
            cpu: "2000m",
            memory: "4000Mi",
            "ephemeral-storage": "5Gi"
        },
        requests: {
            cpu: "1000m",
            memory: "2000Mi",
            "ephemeral-storage": "5Gi"
        }
    },

    FLUENT_BIT: {
        limits: {
            cpu: "2000m",
            memory: "4000Mi",
            "ephemeral-storage": "5Gi"
        },
        requests: {
            cpu: "1000m",
            memory: "2000Mi",
            "ephemeral-storage": "5Gi"
        }
    }
} as const;
