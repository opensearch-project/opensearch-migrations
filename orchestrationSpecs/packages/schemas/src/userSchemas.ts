import {z} from "zod";
import { DEFAULT_RESOURCES, parseK8sQuantity } from "./schemaUtilities";

// ── Schema field metadata ────────────────────────────────────────────
// Downstream dependency names used in checksumFor annotations.
export type ChecksumDependency = 'snapshot' | 'snapshotMigration' | 'replayer';
export type UiTextFormat = 'text' | 'http-endpoint' | 'optional-http-endpoint' | 'cluster-version' | 'k8s-name' | 'oci-image-reference';
export type UiReferencePathTemplateSegment = string | { valueFrom: string[] };
export type UiHint = {
    label?: string;
} & (
    | {
        kind: 'text';
        format?: UiTextFormat;
        pattern?: string;
        message?: string;
        examples?: string[];
    }
    | {
        kind: 'javaRegex';
        message?: string;
        examples?: string[];
        testStrings?: string[];
    }
    | {
        kind: 'reference';
        sourcePath?: string[];
        sourcePaths?: string[][];
        sourcePathTemplate?: UiReferencePathTemplateSegment[];
        allowCustom?: boolean;
        emptyMeansDefault?: string;
        message?: string;
    }
    | {
        kind: 'record';
        addLabel: string;
        keyFormat?: UiTextFormat;
        keyPattern?: string;
        message?: string;
    }
    | {
        kind: 'array';
        addLabel: string;
    }
);

export interface EffectiveDefaultHint {
    label: string;
    value?: unknown;
    description?: string;
}

export type ExternalRefKind = 'kubernetesResource' | 'image' | 'secret' | 'configMap' | 'certManagerIssuer';
export type ExternalRefPurpose =
    | 'http-basic-auth'
    | 'proxy-server-tls'
    | 'proxy-console-client-tls'
    | 'proxy-client-ca'
    | 'file-ref-config-map'
    | 'kafka-scram-password'
    | 'kafka-ca'
    | 'log4j-config'
    | 'transform-entrypoint'
    | 'transform-context-file'
    | 'transform-context-directory'
    | 'cert-manager-issuer';
export type KubernetesExternalRefMatchProfile =
    | 'http-basic-auth-secret'
    | 'tls-secret'
    | 'kafka-scram-password-secret'
    | 'kafka-ca-secret'
    | 'log4j-configmap';
export interface KubernetesResourceType {
    group: string;
    version: string;
    kind: string;
    namespaced: boolean;
    plural?: string;
}
export interface KubernetesExternalRefMatch {
    acceptedSecretTypes?: string[];
    requiredKeys?: string[];
    recommendedKeys?: string[];
    keyPatterns?: string[];
    contentValidationIds?: ExternalContentValidationId[];
}

export type ExternalContentValidationId =
    | 'non-empty-keys'
    | 'pem-certificate-chain'
    | 'pem-private-key'
    | 'tls-certificate-key-pair'
    | 'log4j-properties'
    | 'javascript-syntax'
    | 'python-syntax'
    | 'json';

export type ExternalFormValidationId =
    | 'k8s-name'
    | 'configmap-key'
    | 'non-empty'
    | ExternalContentValidationId;

export interface ExternalResourceFormField {
    name: string;
    label: string;
    input: 'name' | 'text' | 'password' | 'multilineText' | 'secretMultilineText' | 'select';
    required?: boolean;
    default?: string;
    sensitive?: boolean;
    options?: string[];
    validationIds?: ExternalFormValidationId[];
    confirm?: boolean;
}

export interface ExternalResourceCreateDescriptor {
    label: string;
    fields: ExternalResourceFormField[];
    output:
        | {
            kind: 'Secret';
            type: string;
            stringData: Record<string, { fromField: string }>;
        }
        | {
            kind: 'ConfigMap';
            data: Record<string, { fromField: string }>;
        };
    apply:
        | { target: 'scalarName'; nameField: string }
        | { target: 'fileRefConfigMap'; nameField: string; pathField: string };
}

export type ExternalRefSelectionDescriptor =
    | { target: 'scalarName' }
    | {
        target: 'objectRef';
        nameField?: string;
        kindField?: string;
        groupField?: string;
    };

export interface ExternalRefHint {
    kind: ExternalRefKind;
    purpose: ExternalRefPurpose;
    displayName: string;
    description?: string;
    matchProfiles?: KubernetesExternalRefMatchProfile[];
    selection?: ExternalRefSelectionDescriptor;
    k8s?: {
        resourceTypes?: KubernetesResourceType[];
        match?: KubernetesExternalRefMatch;
        /** @deprecated use resourceTypes */
        resource?: 'Secret' | 'ConfigMap' | 'Issuer' | 'ClusterIssuer';
        /** @deprecated use match */
        acceptedSecretTypes?: string[];
        /** @deprecated use matchProfiles or match */
        requiredKeys?: string[];
        /** @deprecated use match */
        recommendedKeys?: string[];
        /** @deprecated use match */
        keyPatterns?: string[];
        /** @deprecated use match */
        contentValidationIds?: ExternalContentValidationId[];
    };
    image?: {
        expects: 'file' | 'directory';
        recommendedPathPatterns?: string[];
        contentValidationIds?: ExternalContentValidationId[];
    };
    create?: ExternalResourceCreateDescriptor;
}

export interface FieldMeta {
    /** Which downstream dependencies include this field in their checksum. */
    checksumFor?: ChecksumDependency[];
    /** Change restriction category for VAP generation. Omit for 'safe'. */
    changeRestriction?: 'impossible' | 'gated';
    /** Optional field that should still appear in the first-pass guided editor. */
    essential?: boolean;
    /** UI editing hint exported into JSON schema and edit-state DTOs. */
    uiHint?: UiHint;
    /** External resource reference hint exported into JSON schema and edit-state DTOs. */
    externalRef?: ExternalRefHint;
    /** Effective runtime default when omission is meaningful and the Zod schema cannot express the resolved value directly. */
    effectiveDefault?: EffectiveDefaultHint;
    /** Advanced field hint exported into JSON schema. */
    expert?: boolean;
}

declare module "zod" {
    interface ZodType {
        checksumFor(...deps: ChecksumDependency[]): this;
        changeRestriction(restriction: 'impossible' | 'gated'): this;
        essential(): this;
        uiHint(hint: UiHint): this;
        externalRef(hint: ExternalRefHint): this;
        effectiveDefault(hint: EffectiveDefaultHint): this;
        expert(): this;
    }
}

z.ZodType.prototype.checksumFor = function(...deps: ChecksumDependency[]) {
    const existing = (this.meta() ?? {}) as FieldMeta;
    return this.meta({ ...existing, checksumFor: deps });
};

z.ZodType.prototype.changeRestriction = function(restriction: 'impossible' | 'gated') {
    const existing = (this.meta() ?? {}) as FieldMeta;
    return this.meta({ ...existing, changeRestriction: restriction });
};

z.ZodType.prototype.essential = function() {
    const existing = (this.meta() ?? {}) as FieldMeta;
    return this.meta({ ...existing, essential: true });
};

z.ZodType.prototype.uiHint = function(hint: UiHint) {
    const existing = (this.meta() ?? {}) as FieldMeta;
    return this.meta({ ...existing, uiHint: hint });
};

z.ZodType.prototype.externalRef = function(hint: ExternalRefHint) {
    const existing = (this.meta() ?? {}) as FieldMeta;
    return this.meta({ ...existing, externalRef: hint });
};

z.ZodType.prototype.effectiveDefault = function(hint: EffectiveDefaultHint) {
    const existing = (this.meta() ?? {}) as FieldMeta;
    return this.meta({ ...existing, effectiveDefault: hint });
};

z.ZodType.prototype.expert = function() {
    const existing = (this.meta() ?? {}) as FieldMeta;
    return this.meta({ ...existing, expert: true });
};

const REQUEST_TRANSFORMER_SUFFIX = " Request transformers modify each captured HTTP request before it is replayed to the target cluster.";
const TUPLE_TRANSFORMER_SUFFIX = " Tuple transformers operate on request-response pairs, enabling stateful transformations that depend on the source cluster's response.";
const METADATA_TRANSFORMER_SUFFIX = " Metadata transformers modify index mappings and settings during migration.";
const DOC_TRANSFORMER_SUFFIX = " Document transformers modify each document during the snapshot backfill (e.g. field renaming, type conversion).";
const EXPERT_FILE_SUFFIX = " [Expert] The file must be mounted into the container by the user (e.g. via Kyverno pod mutation or custom image). Not wired through the workflow by default.";
const JVM_ARGS_DESC = "Additional JVM arguments passed via JDK_JAVA_OPTIONS (e.g. '-Xmx4g -XX:+UseG1GC'). " +
    "If setting -Xmx, ensure the heap size is smaller than the container's memory limit in resources to account for off-heap memory usage.";
const LOGGING_CONFIG_OVERRIDE_DESC = "Name of a Kubernetes ConfigMap containing a custom Log4j2 properties configuration. " +
    "The ConfigMap should have a single key whose value is the Log4j2 properties file content. " +
    "When set, it is mounted into the container and passed via -Dlog4j2.configurationFile. " +
    "See https://logging.apache.org/log4j/2.x/manual/configuration.html#properties for format reference.";
const PROXY_METHOD_REGEX_HINT: UiHint = {
    kind: 'javaRegex',
    message: "Java regex matched against the full HTTP method string; use alternatives like GET|HEAD for multiple methods.",
    examples: ["GET", "GET|HEAD", "POST|PUT|PATCH"],
    testStrings: ["GET", "HEAD", "POST", "PUT", "DELETE"],
};
const PROXY_URI_PATH_REGEX_HINT: UiHint = {
    kind: 'javaRegex',
    message: "Java regex matched against the full request URI path; include .* when you want a contains-style match.",
    examples: ["/_cluster/health", "/_cat/.*", ".*/_search"],
    testStrings: ["/_cluster/health", "/_cat/indices?v", "/my-index/_search", "/_bulk", "/favicon.ico"],
};
const PROXY_METHOD_AND_PATH_REGEX_HINT: UiHint = {
    kind: 'javaRegex',
    message: "Java regex matched against '<METHOD> <URI path>'; include both method and path in the pattern.",
    examples: ["GET /_cluster/health", "(GET|HEAD) /.*", "POST .*/_search"],
    testStrings: ["GET /_cluster/health", "HEAD /", "POST /my-index/_search", "GET /_cat/indices?v", "POST /_bulk"],
};
const PROXY_HEADER_VALUE_REGEX_HINT: UiHint = {
    kind: 'javaRegex',
    message: "Java regex matched against the full value for the named header; header names are matched case-insensitively.",
    examples: ["healthcheck", "Bearer .*", ".*OpenSearch.*"],
    testStrings: ["healthcheck", "Mozilla/5.0 healthcheck", "curl/8.6.0", "Bearer eyJhbGciOi...", "application/json"],
};

const CORE_V1_SECRET: KubernetesResourceType = {group: "", version: "v1", kind: "Secret", namespaced: true};
const CORE_V1_CONFIG_MAP: KubernetesResourceType = {group: "", version: "v1", kind: "ConfigMap", namespaced: true};
const CERT_MANAGER_ISSUER_TYPES: KubernetesResourceType[] = [
    {group: "cert-manager.io", version: "v1", kind: "Issuer", namespaced: true},
    {group: "cert-manager.io", version: "v1", kind: "ClusterIssuer", namespaced: false},
    {group: "awspca.cert-manager.io", version: "v1beta1", kind: "AWSPCAClusterIssuer", namespaced: false},
];

const KUBERNETES_EXTERNAL_REF_MATCH_PROFILES: Record<KubernetesExternalRefMatchProfile, KubernetesExternalRefMatch> = {
    'http-basic-auth-secret': {
        acceptedSecretTypes: ['kubernetes.io/basic-auth', 'Opaque'],
        requiredKeys: ['username', 'password'],
        contentValidationIds: ['non-empty-keys'],
    },
    'tls-secret': {
        acceptedSecretTypes: ['kubernetes.io/tls', 'Opaque'],
        requiredKeys: ['tls.crt', 'tls.key'],
        contentValidationIds: ['tls-certificate-key-pair'],
    },
    'kafka-scram-password-secret': {
        requiredKeys: ['password'],
        contentValidationIds: ['non-empty-keys'],
    },
    'kafka-ca-secret': {
        requiredKeys: ['ca.crt'],
        contentValidationIds: ['pem-certificate-chain'],
    },
    'log4j-configmap': {
        requiredKeys: ['log4j2.properties'],
        contentValidationIds: ['log4j-properties'],
    },
};

function mergeKubernetesMatchProfiles(profiles: KubernetesExternalRefMatchProfile[]): KubernetesExternalRefMatch {
    const result: KubernetesExternalRefMatch = {};
    for (const profile of profiles) {
        const profileMatch = KUBERNETES_EXTERNAL_REF_MATCH_PROFILES[profile];
        for (const key of Object.keys(profileMatch) as (keyof KubernetesExternalRefMatch)[]) {
            const values = profileMatch[key];
            if (values) {
                (result as Record<string, string[]>)[key] = [...new Set([...(result[key] ?? []), ...values])];
            }
        }
    }
    return result;
}

function kubernetesResourceRef(args: {
    purpose: ExternalRefPurpose;
    displayName: string;
    description?: string;
    resourceTypes: KubernetesResourceType[];
    matchProfiles?: KubernetesExternalRefMatchProfile[];
    selection?: ExternalRefSelectionDescriptor;
    create?: ExternalResourceCreateDescriptor;
}): ExternalRefHint {
    const matchProfiles = args.matchProfiles ?? [];
    return {
        kind: 'kubernetesResource',
        purpose: args.purpose,
        displayName: args.displayName,
        ...(args.description ? {description: args.description} : {}),
        ...(matchProfiles.length ? {matchProfiles} : {}),
        selection: args.selection ?? {target: 'scalarName'},
        k8s: {
            resourceTypes: args.resourceTypes,
            ...(matchProfiles.length ? {match: mergeKubernetesMatchProfiles(matchProfiles)} : {}),
        },
        ...(args.create ? {create: args.create} : {}),
    };
}

const LOGGING_CONFIG_MAP_EXTERNAL_REF: ExternalRefHint = {
    ...kubernetesResourceRef({
        purpose: 'log4j-config',
        displayName: 'Log4j2 ConfigMap',
        description: 'Kubernetes ConfigMap containing a Log4j2 properties file.',
        resourceTypes: [CORE_V1_CONFIG_MAP],
        matchProfiles: ['log4j-configmap'],
    }),
    create: {
        label: 'Log4j2 ConfigMap',
        fields: [
            {
                name: 'configMapName',
                label: 'ConfigMap name',
                input: 'name',
                required: true,
                validationIds: ['k8s-name'],
            },
            {
                name: 'properties',
                label: 'log4j2.properties',
                input: 'multilineText',
                required: true,
                validationIds: ['non-empty', 'log4j-properties'],
            },
        ],
        output: {
            kind: 'ConfigMap',
            data: {
                'log4j2.properties': {fromField: 'properties'},
            },
        },
        apply: {
            target: 'scalarName',
            nameField: 'configMapName',
        },
    },
};

const FILE_REF_CONFIG_MAP_EXTERNAL_REF: ExternalRefHint = kubernetesResourceRef({
    purpose: 'file-ref-config-map',
    displayName: 'ConfigMap',
    description: 'Kubernetes ConfigMap containing the referenced file key.',
    resourceTypes: [CORE_V1_CONFIG_MAP],
});

const TLS_SECRET_EXTERNAL_REF: ExternalRefHint = {
    ...kubernetesResourceRef({
        purpose: 'proxy-server-tls',
        displayName: 'TLS Certificate Secret',
        description: "Kubernetes TLS Secret containing 'tls.crt' and 'tls.key' entries.",
        resourceTypes: [CORE_V1_SECRET],
        matchProfiles: ['tls-secret'],
    }),
    create: {
        label: 'TLS Certificate Secret',
        fields: [
            {
                name: 'secretName',
                label: 'Secret name',
                input: 'name',
                required: true,
                validationIds: ['k8s-name'],
            },
            {
                name: 'certificate',
                label: 'Certificate PEM',
                input: 'multilineText',
                required: true,
                validationIds: ['non-empty', 'pem-certificate-chain'],
            },
            {
                name: 'privateKey',
                label: 'Private key PEM',
                input: 'secretMultilineText',
                required: true,
                sensitive: true,
                validationIds: ['non-empty', 'pem-private-key'],
                confirm: true,
            },
        ],
        output: {
            kind: 'Secret',
            type: 'kubernetes.io/tls',
            stringData: {
                'tls.crt': {fromField: 'certificate'},
                'tls.key': {fromField: 'privateKey'},
            },
        },
        apply: {
            target: 'scalarName',
            nameField: 'secretName',
        },
    },
};
const CERT_MANAGER_ISSUER_EXTERNAL_REF: ExternalRefHint = kubernetesResourceRef({
    purpose: 'cert-manager-issuer',
    displayName: 'cert-manager Issuer',
    description: 'cert-manager Issuer, ClusterIssuer, or AWS PCA ClusterIssuer that signs proxy TLS certificates.',
    resourceTypes: CERT_MANAGER_ISSUER_TYPES,
    selection: {target: 'objectRef'},
});
const PROXY_CONSOLE_CLIENT_TLS_EXTERNAL_REF: ExternalRefHint = {
    ...TLS_SECRET_EXTERNAL_REF,
    purpose: 'proxy-console-client-tls',
    displayName: 'Proxy Client Certificate Secret',
    description: "Kubernetes TLS Secret containing the client certificate and private key used by console commands when connecting to the proxy.",
    create: {
        ...TLS_SECRET_EXTERNAL_REF.create!,
        label: 'Proxy Client Certificate Secret',
    },
};
const KAFKA_SCRAM_PASSWORD_EXTERNAL_REF: ExternalRefHint = {
    ...kubernetesResourceRef({
        purpose: 'kafka-scram-password',
        displayName: 'Kafka SCRAM Password Secret',
        description: "Kubernetes Secret containing the SCRAM password for an existing Kafka user. The workflow reads the 'password' key.",
        resourceTypes: [CORE_V1_SECRET],
        matchProfiles: ['kafka-scram-password-secret'],
    }),
    create: {
        label: 'Kafka SCRAM Password Secret',
        fields: [
            {
                name: 'secretName',
                label: 'Secret name',
                input: 'name',
                required: true,
                validationIds: ['k8s-name'],
            },
            {
                name: 'password',
                label: 'Password',
                input: 'password',
                required: true,
                sensitive: true,
                validationIds: ['non-empty'],
                confirm: true,
            },
        ],
        output: {
            kind: 'Secret',
            type: 'Opaque',
            stringData: {
                password: {fromField: 'password'},
            },
        },
        apply: {
            target: 'scalarName',
            nameField: 'secretName',
        },
    },
};
const KAFKA_CA_EXTERNAL_REF: ExternalRefHint = {
    ...kubernetesResourceRef({
        purpose: 'kafka-ca',
        displayName: 'Kafka CA Secret',
        description: "Kubernetes Secret containing the Kafka cluster CA certificate. The workflow mounts the 'ca.crt' key.",
        resourceTypes: [CORE_V1_SECRET],
        matchProfiles: ['kafka-ca-secret'],
    }),
    create: {
        label: 'Kafka CA Secret',
        fields: [
            {
                name: 'secretName',
                label: 'Secret name',
                input: 'name',
                required: true,
                validationIds: ['k8s-name'],
            },
            {
                name: 'certificate',
                label: 'CA certificate PEM',
                input: 'multilineText',
                required: true,
                validationIds: ['non-empty', 'pem-certificate-chain'],
            },
        ],
        output: {
            kind: 'Secret',
            type: 'Opaque',
            stringData: {
                'ca.crt': {fromField: 'certificate'},
            },
        },
        apply: {
            target: 'scalarName',
            nameField: 'secretName',
        },
    },
};
const MIN_POD_REPLICAS_DESC = "Minimum number of pods that must remain available during voluntary Kubernetes disruptions. " +
    "This renders a PodDisruptionBudget minAvailable value for the service. " +
    "The default is 0 so single-replica and single-node dev deployments can still drain; set to 1 or higher for disruption protection, and never above podReplicas.";
import deepmerge from "deepmerge";

export function getZodKeys<T extends z.ZodRawShape>(schema: z.ZodObject<T>): readonly (keyof T)[] {
    return Object.keys(schema.shape) as (keyof T)[];
}

function validateMinPodReplicas(
    ctx: z.RefinementCtx,
    data: {podReplicas?: number, minPodReplicas?: number}
) {
    if (data.minPodReplicas !== undefined && data.podReplicas !== undefined
        && data.minPodReplicas > data.podReplicas) {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `minPodReplicas (${data.minPodReplicas}) must be less than or equal to podReplicas (${data.podReplicas}).`,
            path: ["minPodReplicas"]
        });
    }
}

function scalableServiceWorkflowOptions(serviceName: string, podReplicasDescription: string) {
    return z.object({
        podReplicas: z.number().int().nonnegative().default(1).optional()
            .describe(podReplicasDescription),
        minPodReplicas: z.number().int().nonnegative().default(0).optional()
            .describe(MIN_POD_REPLICAS_DESC.replace("the service", `the ${serviceName} service`)),
    });
}

function withScalableServiceValidation<T extends z.ZodObject<any>>(schema: T): T {
    return schema.superRefine((data, ctx) => validateMinPodReplicas(ctx, data)) as T;
}
class SchemaValidationError extends Error {
    constructor(message: string, public path: string[]) {
        super(`${message} at path: ${path.join('.')}`);
        this.name = 'SchemaValidationError';
    }
}

function validateOptionalDefaultConsistency<T extends z.ZodTypeAny>(
    schema: T,
    path: string[] = []
): T {
    if (schema instanceof z.ZodOptional) {
        const innerType = schema.unwrap();
        const hasDefault = innerType instanceof z.ZodDefault;

        // Unwrap to get the actual type
        const actualType = hasDefault ? (innerType as z.ZodDefault<any>).removeDefault() : innerType;

        // Check if the actual underlying type is complex
        const isComplexType = actualType instanceof z.ZodObject ||
            actualType instanceof z.ZodArray ||
            actualType instanceof z.ZodRecord ||
            actualType instanceof z.ZodUnion ||
            actualType instanceof z.ZodDiscriminatedUnion ||
            actualType instanceof z.ZodIntersection ||
            actualType instanceof z.ZodTuple;

        // Only enforce default requirement for scalar values
        if (!isComplexType && !hasDefault) {
            throw new SchemaValidationError(
                'Optional field must have a default value',
                path
            );
        }

        // Recurse on the actual type
        validateOptionalDefaultConsistency(actualType as z.ZodTypeAny, path);
    } else if (schema instanceof z.ZodDefault) {
        const innerType = (schema as z.ZodDefault<any>).removeDefault();

        // Check if the actual underlying type is complex
        const isComplexType = innerType instanceof z.ZodObject ||
            innerType instanceof z.ZodArray ||
            innerType instanceof z.ZodRecord ||
            innerType instanceof z.ZodUnion ||
            innerType instanceof z.ZodDiscriminatedUnion ||
            innerType instanceof z.ZodIntersection ||
            innerType instanceof z.ZodTuple;

        // Only enforce optional requirement for scalar values
        if (!isComplexType) {
            throw new SchemaValidationError(
                'Default value must be followed by .optional() (use .default(value).optional())',
                path
            );
        }

        // Recurse on the inner type
        validateOptionalDefaultConsistency(innerType as z.ZodTypeAny, path);
    } else if (schema instanceof z.ZodObject) {
        const shape = schema.shape;
        for (const [key, value] of Object.entries(shape)) {
            validateOptionalDefaultConsistency(value as z.ZodTypeAny, [...path, key]);
        }
    } else if (schema instanceof z.ZodArray) {
        validateOptionalDefaultConsistency(schema.element as z.ZodTypeAny, [...path, '[array element]']);
    } else if (schema instanceof z.ZodRecord) {
        validateOptionalDefaultConsistency(schema.valueType as z.ZodTypeAny, [...path, '[record value]']);
    } else if (schema instanceof z.ZodUnion) {
        schema.options.forEach((option, index) => {
            validateOptionalDefaultConsistency(option as z.ZodTypeAny, [...path, `[union option ${index}]`]);
        });
    } else if (schema instanceof z.ZodDiscriminatedUnion) {
        Array.from(schema.options.values()).forEach((option, index) => {
            validateOptionalDefaultConsistency(option as z.ZodTypeAny, [...path, `[discriminated union option ${index}]`]);
        });
    } else if (schema instanceof z.ZodIntersection) {
        validateOptionalDefaultConsistency(schema._def.left as z.ZodTypeAny, [...path, '[intersection left]']);
        validateOptionalDefaultConsistency(schema._def.right as z.ZodTypeAny, [...path, '[intersection right]']);
    } else if (schema instanceof z.ZodTuple) {
        const items = schema._def.items;
        items.forEach((item, index) => {
            validateOptionalDefaultConsistency(item as z.ZodTypeAny, [...path, `[tuple ${index}]`]);
        });
    }
    return schema;
}

export const OPTIONAL_STORAGE_ENDPOINT_PATTERN = /^(?:(?:https?|localstacks?):\/\/[^/]+\/?)?$/;

// Provider-agnostic repository config. The URI scheme (s3:// or gs://) determines the backend.
// S3 bucket names: 3-63 chars; GCS bucket names: up to 220 chars including dotted segments.
export const REPO_CONFIG = z.object({
    repoPathUri: z.string().regex(/^(?:s3:\/\/[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]|gs:\/\/[a-z0-9][a-z0-9._-]{1,220}[a-z0-9])(\/[a-zA-Z0-9!\-_.*'()/]*)?$/)
        .describe("Repository URI in the format 's3://BUCKET_NAME/OPTIONAL_PATH' or 'gs://BUCKET_NAME/OPTIONAL_PATH'. " +
            "The scheme determines the backend. The bucket must already exist and be accessible from the source cluster. " +
            "For GCS, the source cluster must have the `repository-gcs` plugin installed with a configured client."),
    awsRegion: z.string().default("").optional()
        .describe("AWS region where the S3 bucket resides (e.g. 'us-east-2'). Required for s3:// URIs; ignored otherwise."),
    endpoint: z.string().regex(OPTIONAL_STORAGE_ENDPOINT_PATTERN).default("").optional()
        .describe("Override the storage endpoint URL. Supports http://, https://, localstack://, and localstacks:// schemes. " +
            "LocalStack endpoints are automatically resolved to IP addresses during config transformation. " +
            "Used for S3 (LocalStack) or GCS (fake-gcs-server) testing."),
    s3RoleArn: z.string().regex(/^(arn:aws:iam::\d{12}:(user|role|group|policy)\/[a-zA-Z0-9+=,.@_-]+)?$/).default("").optional()
        .describe("IAM role ARN that the source cluster will assume to read/write snapshots to S3. " +
            "Used for s3:// URIs only; ignored for gs://. " +
            "Leave empty if the cluster's own IAM role already has S3 access.")
}).describe("Configuration for a snapshot repository used by the source cluster. " +
    "The URI scheme in repoPathUri determines whether the backend is S3 or GCS. " +
    "For GCS, authentication is expected to be provided to the source cluster out-of-band " +
    "(e.g. via a service-account key loaded into the cluster keystore, or via Workload Identity).");

export const PORT_NUMBER_PATTERN = "(?:[1-9]\\d{0,3}|[1-5]\\d{4}|6[0-4]\\d{3}|65[0-4]\\d{2}|655[0-2]\\d|6553[0-5])";
export const OPTIONAL_PORT_PATTERN = `(?::${PORT_NUMBER_PATTERN})?`;
export const HOSTNAME_PATTERN = "[^:\\/\\s]+";
export const HTTP_ENDPOINT_PATTERN = `^https?:\\/\\/${HOSTNAME_PATTERN}${OPTIONAL_PORT_PATTERN}(?:\\/)?$`;
export const OPTIONAL_HTTP_ENDPOINT_PATTERN = `^(?:https?:\\/\\/${HOSTNAME_PATTERN}${OPTIONAL_PORT_PATTERN}(?:\\/)?)?$`;

export const GENERIC_JSON_OBJECT = z.record(z.string(), z.any());
export const HTTP_HEADER_NAME_PATTERN = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;
export const K8S_NAMING_PATTERN = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/;
export const DNS_LABEL_PATTERN = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
export const DNS_NAME_PATTERN = `^(?:\\*\\.)?${DNS_LABEL_PATTERN}(?:\\.${DNS_LABEL_PATTERN})*$`;
export const K8S_IMAGE_PULL_POLICY = z.enum(["Always", "Never", "IfNotPresent"]);

const K8S_NAME_UI_HINT: UiHint = {
    kind: 'text',
    format: 'k8s-name',
    pattern: K8S_NAMING_PATTERN.source,
    message: "Use a valid Kubernetes DNS name: lowercase letters, numbers, '-' or '.', starting and ending with an alphanumeric character.",
};
const HTTP_ENDPOINT_UI_HINT: UiHint = {
    kind: 'text',
    format: 'http-endpoint',
    pattern: HTTP_ENDPOINT_PATTERN,
    message: "Use an http:// or https:// endpoint with an optional port and trailing slash.",
};
const OPTIONAL_HTTP_ENDPOINT_UI_HINT: UiHint = {
    kind: 'text',
    format: 'optional-http-endpoint',
    pattern: OPTIONAL_HTTP_ENDPOINT_PATTERN,
    message: "Leave empty or use an http:// or https:// endpoint with an optional port and trailing slash.",
};
const DNS_NAME_UI_HINT: UiHint = {
    kind: 'text',
    pattern: DNS_NAME_PATTERN,
    message: "Use a DNS name without a scheme, port, path, or spaces. Wildcards are allowed only as the leftmost label, like *.example.com.",
};
export const OCI_IMAGE_REFERENCE_PATTERN = /^(?=.{1,255}$)(?:(?:localhost|[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*)(?::[0-9]+)?\/)?[a-z0-9]+(?:(?:[._]|__|[-]+)[a-z0-9]+)*(?:\/[a-z0-9]+(?:(?:[._]|__|[-]+)[a-z0-9]+)*)*(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?:@[A-Za-z][A-Za-z0-9]*(?:[+._-][A-Za-z][A-Za-z0-9]*)*:[0-9a-fA-F]{32,})?$/;
const OCI_IMAGE_REFERENCE_MESSAGE = "Use an OCI image reference like repo/name:tag, registry:5000/repo/name:tag, or repo/name@sha256:<digest>; spaces and extra colons are not allowed.";
const OCI_IMAGE_REFERENCE_UI_HINT: UiHint = {
    kind: 'text',
    format: 'oci-image-reference',
    pattern: OCI_IMAGE_REFERENCE_PATTERN.source,
    message: OCI_IMAGE_REFERENCE_MESSAGE,
};

export const FILE_RELATIVE_PATH = z.string()
    .regex(/^(?!\/)(?!.*(?:^|\/)\.\.(?:\/|$)).+$/)
    .describe("Path relative to the mounted image root. Absolute paths and '..' traversal are rejected.");

export const CONFIGMAP_FILE_KEY = z.string()
    .regex(/^(?!\.{1,2}$)(?!\.\.)[A-Za-z0-9._-]+$/)
    .describe("ConfigMap key to expose as a mounted file. Nested paths are not supported for ConfigMap-backed file refs.");

export const CERTIFICATE_DNS_NAME = z.string()
    .min(1)
    .max(253)
    .regex(new RegExp(DNS_NAME_PATTERN))
    .uiHint(DNS_NAME_UI_HINT)
    .describe("DNS name to include as a Subject Alternative Name on the proxy TLS certificate. Use a bare DNS name, not a URL.");

export const FILE_REF_FROM_IMAGE = z.object({
    image: z.string().min(1)
        .regex(OCI_IMAGE_REFERENCE_PATTERN, OCI_IMAGE_REFERENCE_MESSAGE)
        .uiHint(OCI_IMAGE_REFERENCE_UI_HINT)
        .describe("OCI image reference (preferably with digest) whose mounted filesystem contains the requested file."),
    pullPolicy: K8S_IMAGE_PULL_POLICY.default("IfNotPresent").optional()
        .describe("Kubernetes image pull policy. Use 'Always' for mutable tags like 'latest'; leave as 'IfNotPresent' for immutable tags or digests."),
    path: FILE_RELATIVE_PATH
}).strict().describe("Load one file from a mountable OCI image.");

export const FILE_REF_FROM_CONFIGMAP = z.object({
    configMap: z.string().min(1)
        .externalRef(FILE_REF_CONFIG_MAP_EXTERNAL_REF)
        .describe("Name of a pre-existing Kubernetes ConfigMap."),
    path: CONFIGMAP_FILE_KEY
}).strict().describe("Load one file from a Kubernetes ConfigMap key.");

export const FILE_REF = z.union([
    FILE_REF_FROM_IMAGE,
    FILE_REF_FROM_CONFIGMAP
]).describe("Reference to one file from a ConfigMap key or mountable OCI image.");

export const INLINE_JSON_VALUE = z.any()
    .refine(value => value !== undefined, {message: "value is required"});

export const TRANSFORM_CONTEXT_VALUE_DIRECTORY = z.union([
    z.object({
        configMap: z.string().min(1)
            .externalRef(FILE_REF_CONFIG_MAP_EXTERNAL_REF)
            .describe("Name of a pre-existing Kubernetes ConfigMap. Each key becomes one transform context value.")
    }).strict().describe("Load transform context values from every key in a ConfigMap."),
    z.object({
        image: z.string().min(1)
            .regex(OCI_IMAGE_REFERENCE_PATTERN, OCI_IMAGE_REFERENCE_MESSAGE)
            .uiHint(OCI_IMAGE_REFERENCE_UI_HINT)
            .describe("OCI image reference whose mounted filesystem contains transform context files."),
        pullPolicy: K8S_IMAGE_PULL_POLICY.default("IfNotPresent").optional(),
        path: FILE_RELATIVE_PATH.optional()
    }).strict().describe("Load transform context values from files under a directory in a mountable OCI image.")
]).describe("Directory whose immediate files become transform context values.");

export const CONFIG_VALUE_FROM_FILE = z.object({
    fromFile: FILE_REF.describe("File source for one named transform context value.")
}).strict().describe("Load this named context value from a single ConfigMap key or mountable OCI image file.");

export const TRANSFORM_CONTEXT_VALUE = z.union([
    z.object({
        value: INLINE_JSON_VALUE
            .describe("Inline JSON-compatible value made available to the transform under this context name.")
    }).strict().describe("Inline context value stored directly in workflow YAML."),
    CONFIG_VALUE_FROM_FILE
]).describe("One named transform context value. Choose an inline JSON-compatible value or a file-backed value.");

export const TRANSFORM_CONTEXT = z.union([
    z.string(),
    z.object({
        valueDirectories: z.array(TRANSFORM_CONTEXT_VALUE_DIRECTORY)
            .uiHint({kind: "array", label: "value directories", addLabel: "context value directory"})
            .default([])
            .optional()
            .essential()
            .describe("Directories of context values. Each ConfigMap key or immediate file under the mounted directory becomes a named context value."),
        values: z.record(z.string(), TRANSFORM_CONTEXT_VALUE)
            .uiHint({
                kind: "record",
                label: "named values",
                addLabel: "context value",
                message: "Name used by transform code to read this context value."
            })
            .default({})
            .optional()
            .essential()
            .describe("Named transform context values. Add a context name, then choose an inline value or a single file source for that name.")
    }).strict()
]).describe("Optional transform provider context. Values are either inline or loaded at runtime from mounted files.");

export const SCRIPT_TRANSFORM_ENTRY_POINT = z.union([
    z.object({javascript: z.string().min(1).essential()}).strict()
        .describe("**Inline JavaScript** transform source. The script body is stored directly in workflow YAML."),
    z.object({javascriptFile: FILE_REF.essential()}).strict()
        .describe("**External JavaScript file** loaded from a ConfigMap key or mountable OCI image. Use this when package-transforms.sh produced an image reference."),
    z.object({python: z.string().min(1).essential()}).strict()
        .describe("**Inline Python** transform source. The script body is stored directly in workflow YAML."),
    z.object({pythonFile: FILE_REF.essential()}).strict()
        .describe("**External Python file** loaded from a ConfigMap key or mountable OCI image. Use this when package-transforms.sh produced an image reference.")
]).describe("Script transform entry point. Choose **javascript/python** for inline source code, or **javascriptFile/pythonFile** for a script loaded from a ConfigMap key or mountable OCI image.");

export const TRANSFORM_SPEC = z.union([
    z.object({
        entryPoint: SCRIPT_TRANSFORM_ENTRY_POINT
            .describe("Script transform entry point. Choose **javascript/python** for inline source code, or **javascriptFile/pythonFile** for a script loaded from a ConfigMap key or mountable OCI image.")
            .essential(),
        context: TRANSFORM_CONTEXT.optional().essential()
    }).strict().describe("Run a JavaScript/Python transform from inline source code or from an external file source."),
    z.object({
        transformName: z.string().min(1)
            .describe("Name of a built-in transform provider to run.")
            .essential(),
        context: TRANSFORM_CONTEXT.optional().essential()
    }).strict().describe("Run a named built-in transform provider."),
], {error: "Exactly one of entryPoint or transformName is required"})
    .describe("Transform specification. Choose exactly one of entryPoint or transformName.");

export const TRANSFORM_PIPELINE = z.preprocess(
    v => v === undefined || Array.isArray(v) ? v : [v],
    z.array(TRANSFORM_SPEC)
).describe("Ordered transform pipeline. Each item is run in sequence.");

function hasTransformPipelineEntries(value: unknown): boolean {
    return Array.isArray(value) && value.length > 0;
}

function hasConfiguredString(value: unknown): boolean {
    return typeof value === "string" && value.trim().length > 0;
}

function validatePipelineRawConfigConflict(
    ctx: z.RefinementCtx,
    data: Record<string, unknown>,
    pipelineKey: string,
    rawConfigKeys: string[]
) {
    if (!hasTransformPipelineEntries(data[pipelineKey])) {
        return;
    }
    const conflictingKey = rawConfigKeys.find(key => hasConfiguredString(data[key]));
    if (conflictingKey !== undefined) {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `Cannot configure both '${pipelineKey}' and '${conflictingKey}'. Use either the transform pipeline or the raw transformer config source.`,
            path: [pipelineKey]
        });
    }
}

const blankStringAsDisabled = (value: unknown) =>
    typeof value === "string" && value.trim().length === 0 ? "" : value;

const OPTIONAL_ENDPOINT = z.union([z.literal("").transform(() => undefined), z.string()]);

const optionalEndpoint = () => z.preprocess(blankStringAsDisabled, OPTIONAL_ENDPOINT.optional());

// Default is applied in the preprocess step rather than via .default().optional():
// under zod >=4.4 that ordering no longer applies the default for an absent key,
// and .optional().default() makes the input type required. Empty/blank strings
// stay explicit disables, while omitted values default to the collector. The
// metadata default keeps generated JSON/OpenAPI schemas useful for schema-driven
// clients such as interactive config viewers.
const optionalEndpointWithDefault = (defaultValue: string) =>
    z.preprocess(
        (value) => value === undefined ? defaultValue : blankStringAsDisabled(value),
        OPTIONAL_ENDPOINT.optional()
    ).meta({default: defaultValue});

const OTEL_TRACE_COLLECTOR_ENDPOINT = optionalEndpoint()
    .describe("URL for the OpenTelemetry Collector endpoint used for traces (e.g. 'http://otel-trace-collector:4317'). Omit to disable trace export.");

const OTEL_METRICS_COLLECTOR_ENDPOINT = optionalEndpointWithDefault("http://otel-collector:4317")
    .describe("URL for the OpenTelemetry Collector endpoint used for metrics (e.g. 'http://otel-collector:4317'). Set to an empty string to disable metric export.");

export const KAFKA_CLIENT_CONFIG = z.object({
    enableMSKAuth: z.boolean().default(false).optional()
        .describe("Enable SASL/IAM authentication for Amazon MSK. When true, configures the Kafka client with the required SASL properties for IAM-based authentication. Uses the pod's IAM role via EKS Pod Identity."),
    kafkaConnection: z.string()
        .describe("Comma-delimited list of Kafka broker addresses in 'HOSTNAME:PORT' format (e.g. 'broker1:9092,broker2:9092'). " +
            "Required when using an externally managed Kafka cluster.")
        .regex(new RegExp(`^(?:[a-z0-9][-a-z0-9.]*:${PORT_NUMBER_PATTERN}(?:,(?!$)|$))*$`)),
    kafkaTopic: z.string()
        .describe("Default Kafka topic name for this cluster. Can be overridden per-proxy via the capture config's kafkaTopic field.")
        .default(""),
    managedByWorkflow: z.boolean().default(false).optional()
      .describe("Internal flag indicating whether the Kafka cluster is created and resolved by the workflow."),
    listenerName: z.string().default("").optional()
      .describe("Resolved Kafka listener name used by migration applications."),
    authType: z.enum(["none", "scram-sha-512"]).default("none").optional()
      .describe("Resolved Kafka auth mode used by migration applications."),
    secretName: z.string().default("").optional()
      .describe("Resolved Kubernetes secret containing Kafka client credentials."),
    caSecretName: z.string().default("").optional()
      .describe("Resolved Kubernetes secret containing the Kafka cluster CA certificate for TLS trust."),
    kafkaUserName: z.string().default("").optional()
      .describe("Resolved Kafka principal name used by migration applications."),
    topicSpecOverrides: GENERIC_JSON_OBJECT.default({}).optional()
      .describe("Resolved Strimzi KafkaTopic.spec overrides used when the workflow creates the topic resource."),
}).describe("Connection configuration for an externally managed Kafka cluster.");

export const KAFKA_EXISTING_AUTH_CONFIG = z.discriminatedUnion("type", [
    z.object({
        type: z.literal("none")
            .describe("Do not use Kafka client authentication."),
    }),
    z.object({
        type: z.literal("scram-sha-512")
            .describe("Use SASL/SCRAM-SHA-512 authentication for the existing Kafka cluster."),
        secretName: z.string().regex(K8S_NAMING_PATTERN)
            .describe("Name of a Kubernetes Secret containing the Kafka SCRAM password in the 'password' key.")
            .uiHint(K8S_NAME_UI_HINT)
            .externalRef(KAFKA_SCRAM_PASSWORD_EXTERNAL_REF),
        caSecretName: z.string().regex(K8S_NAMING_PATTERN).optional()
            .describe("Optional Kubernetes Secret containing the Kafka cluster CA certificate in the 'ca.crt' key. Omit to use the client runtime's default trust store.")
            .uiHint(K8S_NAME_UI_HINT)
            .externalRef(KAFKA_CA_EXTERNAL_REF),
        kafkaUserName: z.string().regex(K8S_NAMING_PATTERN)
            .uiHint(K8S_NAME_UI_HINT)
            .describe("Kafka SCRAM principal name used by migration clients. The password is read from secretName; this username is not read from the Secret."),
    }),
]);

export const KAFKA_AUTO_CREATE_AUTH_CONFIG = z.discriminatedUnion("type", [
    z.object({
        type: z.literal("none"),
    }),
    z.object({
        type: z.literal("scram-sha-512"),
    }),
]);

export const DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES = {
    partitions: 1,
    replicas: 3,
    config: {
        "retention.ms": 604800000,
        "segment.bytes": 1073741824,
    }
} as const;

// These defaults are resolved during initialization so workflow templates do not
// need to invent their own Kafka defaults. That keeps default ownership here,
// even though some workflow templates currently unpack pieces of the resolved
// Strimzi objects into explicit fields for Argo rendering safety.
const DEFAULT_AUTO_CREATE_KAFKA = {
    clusterSpecOverrides: {
        kafka: {
            readinessProbe: {
                initialDelaySeconds: 10,
                periodSeconds: 10,
                timeoutSeconds: 5,
                failureThreshold: 12,
            },
            livenessProbe: {
                initialDelaySeconds: 30,
                periodSeconds: 15,
                timeoutSeconds: 5,
                failureThreshold: 8,
            },
            config: {
                // RF=3 + minISR=2 on a 3-broker cluster survives one broker down
                // during a rolling restart, drain, or single-node loss without losing
                // writes or quorum. auto.create.topics.enable stays false because
                // migration topics are declared explicitly via KafkaTopic CRs.
                "auto.create.topics.enable": false,
                "offsets.topic.replication.factor": 3,
                "transaction.state.log.replication.factor": 3,
                "transaction.state.log.min.isr": 2,
                "default.replication.factor": 3,
                "min.insync.replicas": 2,
            }
        }
    },
    nodePoolSpecOverrides: {
        // 3 is the minimum broker count that satisfies RF=3/minISR=2 above.
        replicas: 3,
        roles: ["controller", "broker"],
        storage: {
            type: "persistent-claim",
            // Smoke-test size. Real deployments should override.
            size: "2Gi",
            deleteClaim: true,
        },
        template: {
            pod: {
                // Spread brokers one-per-node so a single node disruption can only take
                // one broker. ScheduleAnyway keeps this soft so <3-node dev clusters still
                // schedule instead of wedging Pending.
                topologySpreadConstraints: [
                    {
                        maxSkew: 1,
                        topologyKey: "kubernetes.io/hostname",
                        whenUnsatisfiable: "ScheduleAnyway",
                        labelSelector: {
                            matchExpressions: [
                                {
                                    key: "strimzi.io/name",
                                    operator: "Exists",
                                },
                            ],
                        },
                    },
                ],
            },
        },
    },
    topicSpecOverrides: {
        ...DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES
    },
};

const replaceArrayMerge = (_destinationArray: unknown[], sourceArray: unknown[]) => sourceArray;

export const KAFKA_EXISTING_CLUSTER_CONFIG = z.object({
    enableMSKAuth: z.boolean().default(false).optional(),
    kafkaConnection: z.string()
        .describe("Sequence of <HOSTNAME:PORT> values delimited by ','.")
        .regex(new RegExp(`^(?:[a-z0-9][-a-z0-9.]*:${PORT_NUMBER_PATTERN}(?:,(?!$)|$))*$`)),
    kafkaTopic: z.string().describe("Empty defaults to the name of the target label").default(""),
    auth: KAFKA_EXISTING_AUTH_CONFIG.default({type: "none"}).optional(),
});

export const CPU_QUANTITY = z.string()
    .regex(/^[0-9]+m$/)
    .describe("CPU quantity in Kubernetes millicores (e.g. '100m' = 0.1 CPU, '3500m' = 3.5 CPUs). Must end with 'm'.");

export const MEMORY_QUANTITY = z.string()
    .regex(/^[0-9]+(([EPTGM])i?|Ki|k)$/)
    .describe("Memory quantity in Kubernetes resource format with binary or decimal units (e.g. '512Mi', '2Gi', '4G').");

export const STORAGE_QUANTITY = z.string()
    .regex(/^[0-9]+(([EPTGM])i?|Ki|k)$/)
    .describe("Storage quantity in Kubernetes resource format with binary or decimal units (e.g. '10Gi', '100G').");

export const CONTAINER_RESOURCES = {
    cpu: CPU_QUANTITY.describe("CPU allocation for the container in Kubernetes millicores."),
    memory: MEMORY_QUANTITY.describe("Memory allocation for the container."),
    "ephemeral-storage": STORAGE_QUANTITY.optional()
        .describe("Ephemeral storage allocation for the container. Used for temporary on-disk data such as Lucene index segments during RFS document migration.")
}

export const RESOURCE_REQUIREMENTS = z.object({
    limits: z.object(CONTAINER_RESOURCES).describe("Maximum resource limits for the container. The container will be terminated if it exceeds these limits."),
    requests: z.object(CONTAINER_RESOURCES).describe("Minimum guaranteed resources for the container. Used by the Kubernetes scheduler for pod placement.")
}).describe("Kubernetes compute resource requirements for a container. " +
    "When limits equal requests, the pod gets 'Guaranteed' QoS class and is less likely to be evicted. " +
    "See https://kubernetes.io/docs/concepts/workloads/pods/pod-qos/#guaranteed for details.");

export type ResourceRequirementsType = z.infer<typeof RESOURCE_REQUIREMENTS>;

export const CERT_MANAGER_ISSUER_REF = z.object({
    name: z.string().describe("Name of the cert-manager Issuer or ClusterIssuer resource that will sign the certificate."),
    kind: z.enum(["Issuer", "ClusterIssuer", "AWSPCAClusterIssuer"]).default("ClusterIssuer").optional()
        .describe("Kind of the issuer resource. 'ClusterIssuer' and 'AWSPCAClusterIssuer' are cluster-scoped; 'Issuer' is namespace-scoped."),
    group: z.string().default("cert-manager.io").optional()
        .describe("API group of the issuer. Use 'cert-manager.io' for standard issuers or 'awspca.cert-manager.io' for AWS Private CA issuers."),
}).describe("Reference to a cert-manager issuer that will sign TLS certificates for the proxy.")
    .externalRef(CERT_MANAGER_ISSUER_EXTERNAL_REF);

export const PROXY_TLS_CLIENT_AUTH_CONFIG = z.object({
    trustedClientCaFile: FILE_REF.optional()
        .describe("PEM trusted CA certificate file used to verify client certificates accepted by the capture proxy."),
    trustedClientCaPem: z.string().min(1).optional()
        .describe("Inline PEM trusted CA certificate used to verify client certificates accepted by the capture proxy."),
    consoleClientSecretName: z.string().optional()
        .describe("Name of a Kubernetes TLS Secret containing the client certificate and private key that migration-console commands use when connecting to this mTLS-enabled proxy.")
        .uiHint(K8S_NAME_UI_HINT)
        .externalRef(PROXY_CONSOLE_CLIENT_TLS_EXTERNAL_REF),
}).strict().superRefine((value, ctx) => {
    const trustSourceCount = [
        value.trustedClientCaFile !== undefined,
        value.trustedClientCaPem !== undefined
    ].filter(Boolean).length;

    if (trustSourceCount !== 1) {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "Exactly one of trustedClientCaFile or trustedClientCaPem is required"
        });
    }
}).describe("Optional mutual TLS client-authentication configuration for the capture proxy listener.");

export const PROXY_TLS_CONFIG = z.discriminatedUnion("mode", [
    z.object({
        mode: z.literal("certManager")
            .describe("Use cert-manager to automatically provision and renew a TLS certificate."),
        issuerRef: CERT_MANAGER_ISSUER_REF,
        commonName: z.string().optional()
            .describe("Optional common name (CN) for the TLS certificate subject."),
        dnsNames: z.array(CERTIFICATE_DNS_NAME).min(1)
            .uiHint({kind: 'array', addLabel: 'DNS name'})
            .describe("DNS Subject Alternative Names for the certificate. Must include the proxy's Kubernetes service DNS name (e.g. 'my-proxy.default.svc.cluster.local')."),
        duration: z.string().default("2160h").optional()
            .describe("Requested certificate validity duration in Go duration format (e.g. '2160h' = 90 days)."),
        renewBefore: z.string().default("360h").optional()
            .describe("How long before certificate expiry to trigger renewal (e.g. '360h' = 15 days)."),
        clientAuth: PROXY_TLS_CLIENT_AUTH_CONFIG.optional()
    }).describe("Provision a TLS certificate via cert-manager. A Certificate resource is created and the resulting secret is mounted into the proxy pod."),
    z.object({
        mode: z.literal("existingSecret")
            .describe("Use a pre-existing Kubernetes TLS secret."),
        secretName: z.string()
            .describe("Name of an existing Kubernetes TLS secret containing 'tls.crt' and 'tls.key' entries. The secret is mounted into the proxy pod at /etc/proxy-tls/.")
            .uiHint(K8S_NAME_UI_HINT)
            .externalRef(TLS_SECRET_EXTERNAL_REF),
        clientAuth: PROXY_TLS_CLIENT_AUTH_CONFIG.optional()
    }).describe("Use a pre-existing Kubernetes TLS secret for proxy HTTPS termination."),
    z.object({
        mode: z.literal("plaintext")
            .describe("Explicitly disable TLS. The proxy will serve plaintext HTTP. Use this to opt out of the secure-by-default TLS behavior."),
    }).describe("Explicitly disable TLS termination on the capture proxy."),
]).describe("TLS configuration for the capture proxy. When omitted, a self-signed certificate is automatically provisioned via cert-manager. Specify mode 'plaintext' to opt out.");

export const USER_PROXY_WORKFLOW_OPTIONS = withScalableServiceValidation(z.object({
    ...scalableServiceWorkflowOptions(
        "capture proxy",
        "Number of proxy pod replicas in the Kubernetes Deployment. Increase for higher throughput or availability."
    ).shape,
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe(LOGGING_CONFIG_OVERRIDE_DESC)
        .uiHint(K8S_NAME_UI_HINT)
        .externalRef(LOGGING_CONFIG_MAP_EXTERNAL_REF),
    serviceType: z.enum(["LoadBalancer", "ClusterIP"]).default("LoadBalancer").optional()
        .describe("Expert setting controlling how the capture proxy Kubernetes Service is exposed. " +
            "'LoadBalancer' provisions a cloud/load-balancer-backed Service and waits for load balancer ingress before the proxy is Ready. " +
            "'ClusterIP' exposes the proxy only inside the Kubernetes cluster and waits for the cluster-local Service endpoint before the proxy is Ready.")
        .changeRestriction('impossible'),
    internetFacing: z.boolean().default(false).optional()
        .describe("When true and serviceType is 'LoadBalancer', the proxy's Kubernetes Service is annotated with 'internet-facing' load balancer scheme, making it accessible from outside the VPC.")
        .changeRestriction('impossible'),
    resources: z.preprocess((v) => deepmerge(DEFAULT_RESOURCES.PROXY, (v ?? {})), RESOURCE_REQUIREMENTS)
        .describe("Kubernetes resource limits and requests for the capture proxy container. " +
            "Partial overrides are deep-merged with the built-in defaults. " +
            "By default, limits equal requests, giving the pod 'Guaranteed' QoS (least likely to be evicted). " +
            "Setting requests lower than limits results in 'Burstable' QoS, allowing the pod to use less resources when idle but burst up to the limit.")
        .default(DEFAULT_RESOURCES.PROXY),
}))
    .describe("Kubernetes deployment-level options for the capture proxy.");

export const USER_PROXY_PROCESS_OPTIONS = z.object({
    otelTraceCollectorEndpoint: OTEL_TRACE_COLLECTOR_ENDPOINT,
    otelMetricsCollectorEndpoint: OTEL_METRICS_COLLECTOR_ENDPOINT,
    setHeader: z.array(z.string()).optional()
        .uiHint({kind: 'array', addLabel: 'header'})
        .describe("List of static headers to add to proxied requests, each in 'Header-Name: value' format.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
    destinationConnectionPoolSize: z.number().default(0).optional()
        .describe("Maximum number of persistent connections to the destination (source) cluster. 0 means unlimited connection pooling."),
    destinationConnectionPoolTimeout: z.string()
        .regex(/^[-+]?P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/)
        .default("PT30S").optional()
        .describe("ISO 8601 duration for how long idle connections in the destination pool are kept alive before being closed (e.g. 'PT30S' = 30 seconds, 'PT5M' = 5 minutes)."),
    kafkaClientId: z.string().default("HttpCaptureProxyProducer").optional()
        .describe("Kafka producer client ID used when publishing captured traffic to Kafka. Useful for identifying this proxy in Kafka broker logs and metrics."),
    listenPort: z.number()
        .describe("TCP port the capture proxy listens on for incoming HTTP(S) traffic. This port is exposed via the Kubernetes Service and used to construct the proxy endpoint URL.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('impossible'),
    maxTrafficBufferSize: z.number().min(1).max(1048576).default(1048576).optional()
        .describe("Maximum size in bytes for buffering a single HTTP request/response payload before forwarding to Kafka.")
        .changeRestriction('gated'),
    noCapture: z.boolean().default(false).optional()
        .describe("When true, the proxy forwards traffic to the source cluster without capturing it to Kafka. Useful for TLS termination or routing without traffic recording.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
    numThreads: z.number().default(1).optional()
        .describe("Number of Netty worker threads for the proxy to handle concurrent connections."),
    tls: PROXY_TLS_CONFIG.optional()
        .describe("TLS certificate configuration for HTTPS termination at the proxy. When configured, the proxy serves HTTPS and the TLS secret is mounted at /etc/proxy-tls/.")
        .effectiveDefault({
            label: "cert-manager self-signed",
            description: "When omitted, the workflow creates a cert-manager certificate using the cluster's preconfigured self-signed issuer and generated proxy service DNS names.",
        })
        .changeRestriction('gated'),
    enableMSKAuth: z.boolean().default(false).optional()
        .describe("Enable SASL/IAM authentication for the proxy's Kafka producer when connecting to Amazon MSK. Uses the pod's IAM role via EKS Pod Identity.")
        .changeRestriction('gated'),
    suppressCaptureForHeaderMatch: z.record(
        z.string().regex(HTTP_HEADER_NAME_PATTERN),
        z.string().min(1).uiHint(PROXY_HEADER_VALUE_REGEX_HINT)
            .describe("Java regex pattern to test against the named header's value. The proxy uses full-string matching. Matching requests are forwarded but not recorded.")
    ).default({}).optional()
        .uiHint({
            kind: 'record',
            addLabel: 'header match',
            keyPattern: HTTP_HEADER_NAME_PATTERN.source,
            message: "Use an HTTP header name, such as User-Agent, Authorization, or x-amz-security-token.",
        })
        .describe("Map of HTTP header names to Java regex patterns. Header names are matched case-insensitively. When a request has a matching header value, the request is forwarded but not recorded.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
    suppressCaptureForMethod: z.string().default("").optional()
        .uiHint(PROXY_METHOD_REGEX_HINT)
        .describe("Java regex pattern to test against the HTTP method of the incoming request (for example, 'HEAD' or 'GET|HEAD'). The proxy uses full-string matching. Matching requests are forwarded but not recorded.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
    suppressCaptureForUriPath: z.string().default("").optional()
        .uiHint(PROXY_URI_PATH_REGEX_HINT)
        .describe("Java regex pattern to test against the URI path of the incoming request. The proxy uses full-string matching. Matching requests are forwarded but not recorded.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
    suppressMethodAndPath: z.string().default("").optional()
        .uiHint(PROXY_METHOD_AND_PATH_REGEX_HINT)
        .describe("Java regex pattern to test against the combined 'METHOD /path' value of the incoming request. The proxy uses full-string matching. Matching requests are forwarded but not recorded.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
}).describe("Process-level configuration options for the capture proxy application. These are passed as command-line arguments to the proxy container.");

export const USER_PROXY_WORKFLOW_OPTION_KEYS = getZodKeys(USER_PROXY_WORKFLOW_OPTIONS);
export const USER_PROXY_PROCESS_OPTION_KEYS = getZodKeys(USER_PROXY_PROCESS_OPTIONS);

export const USER_PROXY_OPTIONS = withScalableServiceValidation(z.object({
    ...USER_PROXY_WORKFLOW_OPTIONS.shape,
    ...USER_PROXY_PROCESS_OPTIONS.shape,
}))
    .describe("Process-level and deployment-level configuration options for the capture proxy.");

export const USER_REPLAYER_WORKFLOW_OPTIONS = withScalableServiceValidation(z.object({
    ...scalableServiceWorkflowOptions(
        "traffic replayer",
        "Number of replayer pod replicas in the Kubernetes Deployment. Each replica independently consumes from Kafka and replays traffic to the target."
    ).shape,
    jvmArgs: z.string().default("").optional()
        .describe(JVM_ARGS_DESC),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe(LOGGING_CONFIG_OVERRIDE_DESC)
        .uiHint(K8S_NAME_UI_HINT)
        .externalRef(LOGGING_CONFIG_MAP_EXTERNAL_REF),
    useLocalStack: z.boolean().default(false).optional()
        .describe("[Internal] Mount local test AWS credentials for LocalStack-backed tuple S3 output. Workflow-only testing hook; not passed to the replayer process and not intended for production use."),
    resources: z.preprocess((v) => deepmerge(DEFAULT_RESOURCES.REPLAYER, (v ?? {})), RESOURCE_REQUIREMENTS)
        .describe("Kubernetes resource limits and requests for the replayer container. " +
            "Partial overrides are deep-merged with the built-in defaults. " +
            "By default, limits equal requests, giving the pod 'Guaranteed' QoS (least likely to be evicted). " +
            "Setting requests lower than limits results in 'Burstable' QoS, allowing the pod to use less resources when idle but burst up to the limit.")
        .default(DEFAULT_RESOURCES.REPLAYER),
}))
    .describe("Kubernetes deployment-level options for the traffic replayer.");

export const USER_REPLAYER_PROCESS_OPTIONS = z.object({
    kafkaTrafficEnableMSKAuth: z.boolean().default(false).optional()
        .describe("Enable SASL/IAM authentication for the replayer's Kafka consumer when connecting to Amazon MSK. Uses the pod's IAM role via EKS Pod Identity.")
        .changeRestriction('impossible'),
    kafkaTrafficPropertyFile: z.string().optional()
        .describe("[Expert] Path to a Java properties file with additional or overridden Kafka consumer configuration. The file must be mounted into the container by the user (e.g. via Kyverno pod mutation or custom image). Not wired through the workflow by default.")
        .changeRestriction('impossible'),
    lookaheadTimeSeconds: z.number().default(400).optional()
        .describe("Number of seconds of captured traffic to buffer ahead of the current replay position. Must be strictly greater than observedPacketConnectionTimeout. Larger values improve throughput but increase memory usage."),
    maxConcurrentRequests: z.number().default(10000).optional()
        .describe("Maximum number of HTTP requests that can be in-flight simultaneously to the target cluster. Limits concurrency to prevent overwhelming the target."),
    numClientThreads: z.number().default(0).optional()
        .describe("Number of threads used to send replayed requests to the target. 0 uses the Netty event loop (typically number of available processors)."),
    nonRetryableDocExceptionTypes: z.array(z.string()).optional()
        .uiHint({kind: 'array', addLabel: 'exception type'})
        .describe("List of document-level exception types that should not be retried during bulk replay. " +
            "These errors still count as failures in the output but are not retried because they are " +
            "deterministic client or mapping errors that will produce the same result on every attempt. " +
            "When omitted, defaults to a built-in set including version_conflict_engine_exception, " +
            "mapper_parsing_exception, strict_dynamic_mapping_exception, and others. " +
            "Set explicitly to override the defaults entirely (not additive). " +
            "Common values: version_conflict_engine_exception, mapper_parsing_exception, " +
            "illegal_argument_exception, resource_already_exists_exception."),
    observedPacketConnectionTimeout: z.number().default(360).optional()
        .describe("Seconds of inactivity on a captured connection before assuming it was terminated in the original traffic stream. Must be strictly less than lookaheadTimeSeconds.")
        .essential(),
    otelTraceCollectorEndpoint: OTEL_TRACE_COLLECTOR_ENDPOINT,
    otelMetricsCollectorEndpoint: OTEL_METRICS_COLLECTOR_ENDPOINT,
    quiescentPeriodMs: z.number().default(5000).optional()
        .describe("Milliseconds to delay the first request on a resumed connection after a Kafka partition reassignment. Prevents request bursts during rebalancing."),
    removeAuthHeader: z.boolean().default(false).optional()
        .describe("Remove the Authorization header from replayed requests without replacing it. Useful when the target uses a different auth mechanism (e.g. SigV4) configured separately.")
        .changeRestriction('gated'),
    speedupFactor: z.number().default(1.1).optional()
        .describe("Multiplier to accelerate replay timing relative to the original captured traffic. 1.0 = real-time, 2.0 = double speed.")
        .essential(),
    targetServerResponseTimeoutSeconds: z.number().default(150).optional()
        .describe("Maximum seconds to wait for a response from the target cluster before timing out a replayed request.")
        .essential(),
    transformerConfig: z.string().optional()
        .describe("Inline request transformer configuration as a JSON string." + REQUEST_TRANSFORMER_SUFFIX)
        .expert()
        .changeRestriction('gated'),
    transformerConfigEncoded: z.string().optional()
        .describe("Base64-encoded request transformer configuration, for configurations that would be cumbersome to otherwise encode in a JSON field." + REQUEST_TRANSFORMER_SUFFIX)
        .expert()
        .changeRestriction('gated'),
    transformerConfigFile: z.string().optional()
        .describe("Path to a JSON file containing request transformer configuration." + REQUEST_TRANSFORMER_SUFFIX + EXPERT_FILE_SUFFIX)
        .expert()
        .changeRestriction('gated'),
    requestTransforms: TRANSFORM_PIPELINE.optional()
        .describe("Request transform pipeline. Generates the existing transformerConfig inline JSON option.")
        .essential()
        .changeRestriction('gated'),
    tupleTransformerConfig: z.string().optional()
        .describe("Inline tuple transformer configuration as a JSON string." + TUPLE_TRANSFORMER_SUFFIX)
        .expert()
        .changeRestriction('gated'),
    tupleTransformerConfigBase64: z.string().optional()
        .describe("Base64-encoded tuple transformer configuration." + TUPLE_TRANSFORMER_SUFFIX)
        .expert()
        .changeRestriction('gated'),
    tupleTransformerConfigFile: z.string().optional()
        .describe("Path to a JSON file containing tuple transformer configuration." + TUPLE_TRANSFORMER_SUFFIX + EXPERT_FILE_SUFFIX)
        .expert()
        .changeRestriction('gated'),
    tupleTransforms: TRANSFORM_PIPELINE.optional()
        .describe("Tuple transform pipeline. Generates the existing tupleTransformerConfig inline JSON option.")
        .essential()
        .changeRestriction('gated'),
    tupleS3Bucket: z.string().optional()
        .describe("S3 bucket for tuple output. When set, tuples are written directly to S3.")
        .changeRestriction('gated'),
    tupleS3Region: z.string().optional()
        .describe("AWS region for the tuple S3 bucket. Required when tupleS3Bucket is set.")
        .changeRestriction('gated'),
    tupleS3Prefix: z.string().default("tuples/").optional()
        .describe("S3 key prefix for tuple objects.")
        .changeRestriction('gated'),
    tupleS3Endpoint: z.string().regex(new RegExp(OPTIONAL_HTTP_ENDPOINT_PATTERN)).default("").optional()
        .describe("Custom S3 endpoint URL for tuple output.")
        .changeRestriction('gated'),
    tupleMaxBufferSeconds: z.number().default(60).optional()
        .describe("Maximum seconds before rotating/uploading a tuple file to S3.")
        .changeRestriction('gated'),
    tupleMaxFileSizeMb: z.number().default(256).optional()
        .describe("Maximum uncompressed size in MB before rotating a tuple file to S3.")
        .changeRestriction('gated'),
    tupleMaxPerFile: z.number().default(0).optional()
        .describe("Maximum number of tuples per S3 object. 0 means no count limit.")
        .changeRestriction('gated'),
    userAgent: z.string().optional()
        .describe("String appended to the User-Agent header on all replayed requests to the target cluster. Useful for identifying replayed traffic in target cluster logs."),
}).describe("Process-level configuration options for the traffic replayer application. These control how captured traffic is read from Kafka and replayed to the target cluster.");

export const USER_REPLAYER_WORKFLOW_OPTION_KEYS = getZodKeys(USER_REPLAYER_WORKFLOW_OPTIONS);
export const USER_REPLAYER_PROCESS_OPTION_KEYS = getZodKeys(USER_REPLAYER_PROCESS_OPTIONS);

export const USER_REPLAYER_OPTIONS = z.object({
    ...USER_REPLAYER_WORKFLOW_OPTIONS.shape,
    ...USER_REPLAYER_PROCESS_OPTIONS.shape,
}).superRefine((data, ctx) => {
    validateMinPodReplicas(ctx, data);
    validatePipelineRawConfigConflict(ctx, data, "requestTransforms", [
        "transformerConfig",
        "transformerConfigEncoded",
        "transformerConfigFile"
    ]);
    validatePipelineRawConfigConflict(ctx, data, "tupleTransforms", [
        "tupleTransformerConfig",
        "tupleTransformerConfigBase64",
        "tupleTransformerConfigFile"
    ]);
    if (hasConfiguredString(data.tupleS3Bucket) && !hasConfiguredString(data.tupleS3Region)) {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "'tupleS3Region' is required when 'tupleS3Bucket' is configured.",
            path: ["tupleS3Region"]
        });
    }

    if (data.lookaheadTimeSeconds !== undefined && data.observedPacketConnectionTimeout !== undefined
        && data.lookaheadTimeSeconds <= data.observedPacketConnectionTimeout) {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `lookaheadTimeSeconds (${data.lookaheadTimeSeconds}) must be strictly greater than observedPacketConnectionTimeout (${data.observedPacketConnectionTimeout})`,
            path: ['lookaheadTimeSeconds']
        });
    }
});

// Note: noWait is not included here as it is hardcoded to true in the workflow.
// The workflow manages snapshot completion polling separately via checkSnapshotStatus.
export const USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS = z.object({
    snapshotPrefix: z.string().default("").optional()
        .describe("Prefix for auto-generated snapshot names. When set, the snapshot name is '<snapshotPrefix>_<uniqueId>'. When empty, defaults to '<sourceLabel>_<uniqueId>'."),
    jvmArgs: z.string().default("").optional()
        .describe(JVM_ARGS_DESC),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe(LOGGING_CONFIG_OVERRIDE_DESC)
        .uiHint(K8S_NAME_UI_HINT)
        .externalRef(LOGGING_CONFIG_MAP_EXTERNAL_REF)
}).describe("Workflow-level options for snapshot creation, controlling naming and JVM configuration.");

export const USER_CREATE_SNAPSHOT_PROCESS_OPTIONS = z.object({
    otelTraceCollectorEndpoint: OTEL_TRACE_COLLECTOR_ENDPOINT,
    otelMetricsCollectorEndpoint: OTEL_METRICS_COLLECTOR_ENDPOINT,
    indexAllowlist: z.array(z.string()).default([]).optional()
        .uiHint({kind: 'array', addLabel: 'index pattern'})
        .describe("Filters which indices are captured at the snapshot layer — evaluated by the source cluster when the snapshot is created. " +
            "Entries use the cluster's native multi-index expression syntax (the same format accepted by the _snapshot API's 'indices' field): " +
            "exact names (e.g. 'logs-2024-01'), wildcards (e.g. 'logs-*'), and exclusions via a leading '-' (e.g. '-*-archive'). " +
            "The entries are joined with commas and sent verbatim as the 'indices' field of the snapshot creation request; this is NOT a regex. " +
            "Only the matching indices end up in the snapshot, so downstream stages have no way to recover indices excluded here. " +
            "To further narrow which indices are migrated after the snapshot is taken (including with 'regex:' patterns), use the metadata or RFS indexAllowlist, " +
            "which filter client-side on the snapshot contents. " +
            "An empty list includes all indices."),
    maxSnapshotRateMbPerNode: z.number().default(0).optional()
        .describe("Maximum snapshot throughput in MB/s per data node. 0 means no rate limiting. Use to reduce I/O impact on the source cluster during snapshot creation."),
    compressionEnabled: z.boolean().default(false).optional()
        .describe("[Expert] Enables metadata compression for the snapshot. Must be set to false for Elasticsearch 1.x sources, as compressed snapshot metadata is not supported by the snapshot reader for that version."),
    includeGlobalState: z.boolean().default(true).optional()
        .describe("[Expert] Includes cluster global state (persistent settings, templates, etc.) in the snapshot. " +
            "Only disable if metadata migration encounters template processing issues that cannot be resolved via an allowlist."),
}).describe("Process-level options for the CreateSnapshot command, controlling which indices are snapshotted and rate limiting.");

export const USER_CREATE_SNAPSHOT_WORKFLOW_OPTION_KEYS = getZodKeys(USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS);
export const USER_CREATE_SNAPSHOT_PROCESS_OPTION_KEYS = getZodKeys(USER_CREATE_SNAPSHOT_PROCESS_OPTIONS);

export const USER_CREATE_SNAPSHOT_OPTIONS = z.object({
    ...USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS.shape,
    ...USER_CREATE_SNAPSHOT_PROCESS_OPTIONS.shape,
});

export const USER_METADATA_WORKFLOW_OPTIONS = z.object({
    jvmArgs: z.string().default("").optional()
        .describe(JVM_ARGS_DESC),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe(LOGGING_CONFIG_OVERRIDE_DESC)
        .uiHint(K8S_NAME_UI_HINT)
        .externalRef(LOGGING_CONFIG_MAP_EXTERNAL_REF),
    skipEvaluateApproval: z.boolean().default(false).optional()
        .describe("When true, skips the manual approval gate after the metadata evaluation step. The evaluation step analyzes what metadata changes would be applied without making changes."),
    skipMigrateApproval: z.boolean().default(false).optional()
        .describe("When true, skips the manual approval gate after the metadata migration step. The migration step applies the evaluated metadata changes to the target cluster.")
}).describe("Workflow-level options for metadata migration, controlling JVM settings and approval gates.");

export const USER_METADATA_PROCESS_OPTIONS = z.object({
    componentTemplateAllowlist: z.array(z.string()).default([]).optional()
        .uiHint({kind: 'array', addLabel: 'component template'})
        .describe("List of component template names to include in the metadata migration. " +
            "Each entry is either an exact name or a regex pattern prefixed with 'regex:'. " +
            "An empty list includes all non-system component templates.")
        .essential(),
    indexAllowlist: z.array(z.string()).default([]).optional()
        .uiHint({kind: 'array', addLabel: 'index pattern'})
        .describe("Filters which indices are migrated at the metadata stage — evaluated client-side on the snapshot contents after the snapshot has been taken. " +
            "Each entry is either an exact index name (e.g. 'my-index') or a regex pattern prefixed with 'regex:' (e.g. 'regex:logs-.*'). " +
            "Applies only among indices already captured in the snapshot; to exclude an index from the snapshot itself, use the CreateSnapshot indexAllowlist. " +
            "An empty list includes all non-system indices.")
        .essential(),
    indexTemplateAllowlist: z.array(z.string()).default([]).optional()
        .uiHint({kind: 'array', addLabel: 'index template'})
        .describe("List of index template names to include in the metadata migration. " +
            "Each entry is either an exact name or a regex pattern prefixed with 'regex:'. " +
            "An empty list includes all non-system index templates.")
        .essential(),

    allowLooseVersionMatching: z.boolean().default(true).optional()
        .describe("[Expert] Allows migration between clusters with non-exact version compatibility (e.g. ES 7.x to OS 2.x). " +
            "Only disable if metadata has parsing issues on snapshots that require strict version matching."),
    clusterAwarenessAttributes: z.number().default(1).optional()
        .describe("Number of shard allocation awareness attributes to preserve during metadata migration. Controls how index settings related to cluster topology are handled."),
    otelTraceCollectorEndpoint: OTEL_TRACE_COLLECTOR_ENDPOINT,
    otelMetricsCollectorEndpoint: OTEL_METRICS_COLLECTOR_ENDPOINT,
    output: z.enum(["HUMAN_READABLE", "JSON"]).default("HUMAN_READABLE").optional()
        .describe("Output format for the metadata migration evaluation report. 'HUMAN_READABLE' for formatted text, 'JSON' for machine-parseable output."),
    transformerConfigBase64: z.string().default("").optional()
        .describe("Base64-encoded JSON transformer configuration." + METADATA_TRANSFORMER_SUFFIX)
        .expert(),
    transformerConfig: z.string().optional()
        .describe("Inline JSON transformer configuration. Keys are transformer names and values are their configuration." + METADATA_TRANSFORMER_SUFFIX)
        .expert(),
    transformerConfigFile: z.string().optional()
        .describe("Path to a JSON file containing transformer configuration." + METADATA_TRANSFORMER_SUFFIX + EXPERT_FILE_SUFFIX)
        .expert(),
    metadataTransforms: TRANSFORM_PIPELINE.optional()
        .describe("Metadata transform pipeline. Generates the existing transformerConfig inline JSON option.")
        .essential()
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('impossible'),
    enableSourcelessMigrations: z.boolean().default(false).optional()
        .describe("Enable migration of indices that have _source disabled or partially filtered (includes/excludes). " +
            "When enabled, document backfill will reconstruct documents from stored fields and doc_values. " +
            "Without this flag, metadata migration will fail if any selected index has _source disabled or partially filtered.")
        .changeRestriction('impossible'),
    useRecoverySource: z.boolean().default(false).optional()
        .describe("When enabled, treat the _recovery_source stored field (present in ES 7+ / OpenSearch snapshots " +
            "with soft-deletes) as _source. This field is transient and may not be present for all documents, " +
            "so results can be inconsistent. Use only when reconstruction from doc_values and stored fields is insufficient.")
        .changeRestriction('impossible'),
}).describe("Process-level options for the metadata migration command, controlling which metadata is migrated and how it is transformed.");

export const USER_METADATA_WORKFLOW_OPTION_KEYS = getZodKeys(USER_METADATA_WORKFLOW_OPTIONS);
export const USER_METADATA_PROCESS_OPTION_KEYS = getZodKeys(USER_METADATA_PROCESS_OPTIONS);

export const USER_METADATA_OPTIONS = z.object({
    ...USER_METADATA_WORKFLOW_OPTIONS.shape,
    ...USER_METADATA_PROCESS_OPTIONS.shape,
}).superRefine((data, ctx) => {
    validatePipelineRawConfigConflict(ctx, data, "metadataTransforms", [
        "transformerConfig",
        "transformerConfigBase64",
        "transformerConfigFile"
    ]);
});

export const USER_RFS_WORKFLOW_OPTIONS = withScalableServiceValidation(z.object({
    ...scalableServiceWorkflowOptions(
        "RFS document backfill",
        "Number of RFS worker pod replicas. Each replica independently acquires and processes snapshot shards in parallel —" +
            " throughput scales linearly up to the total number of source shards."
    ).shape,
    podReplicas: scalableServiceWorkflowOptions(
        "RFS document backfill",
        "Number of RFS worker pod replicas. Each replica independently acquires and processes snapshot shards in parallel —" +
            " throughput scales linearly up to the total number of source shards."
    ).shape.podReplicas.essential(),
    jvmArgs: z.string().default("").optional()
        .describe(JVM_ARGS_DESC),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe(LOGGING_CONFIG_OVERRIDE_DESC)
        .uiHint(K8S_NAME_UI_HINT)
        .externalRef(LOGGING_CONFIG_MAP_EXTERNAL_REF),
    skipApproval: z.boolean().default(false).optional()
        .describe("When true, skips the manual approval gate after the document backfill completes. Useful for automated pipelines where human approval is not needed."),
    useTargetClusterForWorkCoordination: z.boolean().default(false)
        .describe("[Expert] When true, uses the target OpenSearch cluster for RFS work coordination (lease management and shard assignment). " +
            "When false (default), a dedicated single-node OpenSearch coordinator cluster is automatically deployed within the Kubernetes cluster, used for the lifetime of the migration, then torn down on completion. " +
            "Using a dedicated coordinator avoids adding coordination overhead to the target cluster."),
    resources: z.preprocess((v) => deepmerge(DEFAULT_RESOURCES.RFS, (v ?? {})), RESOURCE_REQUIREMENTS)
        .pipe(RESOURCE_REQUIREMENTS.extend({
            requests: RESOURCE_REQUIREMENTS.shape.requests.extend({
                "ephemeral-storage":
                    RESOURCE_REQUIREMENTS.shape.requests.shape["ephemeral-storage"].describe(
                        "Ephemeral storage for RFS temporary Lucene segments. If omitted, automatically computed as ceil(2.5 * maxShardSizeBytes) to ensure sufficient space for the largest shard."
                    ),
            }),
        }))
        .describe("Kubernetes resource limits and requests for the RFS container. " +
            "Partial overrides are deep-merged with the built-in defaults. " +
            "By default, limits equal requests, giving the pod 'Guaranteed' QoS (least likely to be evicted). " +
            "Setting requests lower than limits results in 'Burstable' QoS. " +
            "Ephemeral storage is auto-calculated from maxShardSizeBytes if not specified."),
}))
    .describe("Kubernetes deployment-level options for the Reindex From Snapshot (RFS) document backfill.");

export const USER_RFS_PROCESS_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]).optional()
        .uiHint({kind: 'array', addLabel: 'index pattern'})
        .describe("Filters which indices are migrated by the document backfill (RFS) — evaluated client-side on the snapshot contents after the snapshot has been taken. " +
            "Each entry is either an exact index name or a regex pattern prefixed with 'regex:' (e.g. 'regex:logs-.*'). " +
            "Applies only among indices already captured in the snapshot; to exclude an index from the snapshot itself, use the CreateSnapshot indexAllowlist. " +
            "An empty list includes all non-system indices from the snapshot.")
        .essential()
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    allowLooseVersionMatching: z.boolean().default(true).optional()
        .describe("[Expert] Allows document migration between clusters with non-exact version compatibility. " +
            "Only disable if snapshot parsing issues require strict version matching.")
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    docTransformerConfigBase64: z.string().default("").optional()
        .describe("Base64-encoded JSON transformer configuration." + DOC_TRANSFORMER_SUFFIX)
        .expert()
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    docTransformerConfig: z.string().optional()
        .describe("Inline JSON transformer configuration. Keys are transformer names and values are their configuration." + DOC_TRANSFORMER_SUFFIX)
        .expert()
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    docTransformerConfigFile: z.string().optional()
        .describe("Path to a JSON file containing transformer configuration." + DOC_TRANSFORMER_SUFFIX + EXPERT_FILE_SUFFIX)
        .expert()
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    documentTransforms: TRANSFORM_PIPELINE.optional()
        .describe("Document transform pipeline. Generates the existing docTransformerConfig inline JSON option.")
        .essential()
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    documentsPerBulkRequest: z.number().default(0x7fffffff).optional()
        .describe("Maximum number of documents per bulk indexing request to the target cluster. Lower values reduce per-request latency but increase overhead."),
    documentsSizePerBulkRequest: z.number().default(10*1024*1024).optional()
        .describe("Maximum aggregate document size in bytes per bulk indexing request. Individual documents larger than this limit are sent as single-document requests."),
    initialLeaseDuration: z.string()
        .regex(/^[-+]?P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/)
        .default("PT1H").optional()
        .describe("[Expert] ISO 8601 duration for the initial work item lease in the coordination store (e.g. 'PT1H' = 1 hour, 'PT10M' = 10 minutes). " +
            "If a worker fails to complete a shard within this duration, the lease expires and another worker can pick it up, doubling the lease duration on each retry. " +
            "Increase for very large shards (>200GB) to reduce the number of re-downloads per shard needed to complete the migration.")
        .changeRestriction('gated'),
    maxConnections: z.number().default(10).optional()
        .describe("Maximum number of concurrent HTTP connections from each RFS worker to the target cluster for bulk indexing.")
        .changeRestriction('gated'),
    maxShardSizeBytes: z.number().default(80*1024*1024*1024).optional()
        .describe("Expected maximum shard size in bytes. Used to auto-calculate ephemeral storage requirements as ceil(2.5 * maxShardSizeBytes). Set this to match your largest shard to ensure sufficient disk space for Lucene segment processing.")
        .changeRestriction('gated'),
    otelTraceCollectorEndpoint: OTEL_TRACE_COLLECTOR_ENDPOINT,
    otelMetricsCollectorEndpoint: OTEL_METRICS_COLLECTOR_ENDPOINT,
    serverGeneratedIds: z.enum(["AUTO", "ALWAYS", "NEVER"]).default("AUTO").optional()
        .describe("Controls document ID generation on the target. " +
            "'AUTO': auto-detect serverless TIMESERIES/VECTOR collections and enable server-generated IDs. " +
            "'ALWAYS': always use server-generated IDs (discards source IDs). " +
            "'NEVER': always preserve source document IDs (may fail on serverless TIMESERIES/VECTOR collections)."),
    emitDocType: z.enum(["AUTO", "ON", "OFF"]).default("AUTO").optional()
        .describe("Controls whether the ES _type field is propagated into bulk action-line metadata. " +
            "'AUTO' (default): emit _type only when the source is ES 6 or older AND a document transformer " +
            "is configured (e.g. TypeMappingSanitizationTransformerProvider for multi-type indices). " +
            "'ON': always emit _type. 'OFF': never emit _type.")
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    allowedDocExceptionTypes: z.array(z.string()).default([]).optional()
        .uiHint({kind: 'array', addLabel: 'exception type'})
        .describe("List of document-level exception types to treat as successful operations during bulk migration. " +
            "Documents that fail with these errors are not retried and not counted as failures — they are silently accepted. " +
            "Use this for idempotent migrations where certain errors are expected and harmless. " +
            "For example, set to ['version_conflict_engine_exception'] when migrating into a data stream " +
            "where backing index writes may conflict with existing documents. " +
            "Defaults to empty (all errors are treated as failures). " +
            "See BulkDocErrorTypes for common OpenSearch exception type strings."),
    coordinatorRetryMaxRetries: z.number().default(7).optional()
        .describe("[Expert] Maximum number of retries when marking work items as completed on the coordinator."),
    coordinatorRetryInitialDelayMs: z.number().default(1000).optional()
        .describe("[Expert] Initial delay in milliseconds for coordinator completion retries. Doubles with each attempt up to coordinatorRetryMaxDelayMs."),
    coordinatorRetryMaxDelayMs: z.number().default(64000).optional()
        .describe("[Expert] Maximum delay in milliseconds for any single coordinator completion retry."),
    enableSourcelessMigrations: z.boolean().default(false).optional()
        .describe("Enable migration of indices that have _source disabled or partially filtered (includes/excludes). " +
            "When enabled, documents are reconstructed from stored fields and doc_values instead of _source. " +
            "Without this flag, migration of sourceless indices will fail with an error.")
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    useRecoverySource: z.boolean().default(false).optional()
        .describe("When enabled, treat the _recovery_source stored field (present in ES 7+ / OpenSearch snapshots " +
            "with soft-deletes) as _source. This field is transient and may not be present for all documents, " +
            "so results can be inconsistent. Use only when reconstruction from doc_values and stored fields is insufficient.")
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    positionGapStopword: z.string().default("a").optional()
        .describe("Token used to fill skipped Lucene positions when reconstructing analyzed-text fields from postings. " +
            "ES preserves position increments for stop-word-filtered tokens (e.g. 'i like the tree' with stopword 'the' indexes " +
            "at positions 0,1,3 — position 2 is consumed by 'the' but the term itself is dropped). Without filler the " +
            "reconstructor joins on spaces and OS re-tokenizes the document at consecutive positions [0,1,2], silently " +
            "changing slop / proximity / phrase semantics on migrated documents. The reconstructor splices this token " +
            "into the gap so OS — assumed to have the same token configured as a stopword — re-creates the original " +
            "[0,1,3] postings while indexing. The token MUST be on the target's stopword list or it leaks into search " +
            "results; 'a' is a safe default for the english / standard analyzers. " +
            "Pass an empty string to opt out and fall back to the legacy multi-space behaviour. " +
            "Default: 'a'.")
        .checksumFor('replayer')
        .changeRestriction('impossible'),
}).describe("Process-level options for the RFS document backfill command, controlling indexing behavior, concurrency, and transformations.");

export const USER_RFS_WORKFLOW_OPTION_KEYS = getZodKeys(USER_RFS_WORKFLOW_OPTIONS);
export const USER_RFS_PROCESS_OPTION_KEYS = getZodKeys(USER_RFS_PROCESS_OPTIONS);

export const USER_RFS_OPTIONS = z.object({
    ...USER_RFS_WORKFLOW_OPTIONS.shape,
    ...USER_RFS_PROCESS_OPTIONS.shape,
})
    .superRefine((data, ctx) => {
        validateMinPodReplicas(ctx, data);
        validatePipelineRawConfigConflict(ctx, data, "documentTransforms", [
            "docTransformerConfig",
            "docTransformerConfigBase64",
            "docTransformerConfigFile"
        ]);
    })
    .transform((data) => {
        const requestEphemeral = data.resources?.requests?.["ephemeral-storage"];
        const userRequestEphemeralStorageBytes = requestEphemeral
            ? parseK8sQuantity(requestEphemeral)
            : undefined;
        const limitsEphemeral = data.resources?.limits?.["ephemeral-storage"];
        const userLimitsEphemeralStorageBytes = limitsEphemeral
            ? parseK8sQuantity(limitsEphemeral)
            : undefined;
        const minimumEphemeralBytes = Math.ceil(2.5 * (data.maxShardSizeBytes??0))

        if (userRequestEphemeralStorageBytes != undefined && userRequestEphemeralStorageBytes < minimumEphemeralBytes) {
            throw new Error(
                `Resource requests for RFS storage of ${userRequestEphemeralStorageBytes} is too low to support maxShardSizeBytes value of ${data.maxShardSizeBytes}.
            Increase to at least ${minimumEphemeralBytes} to support the migration or decrease maxShardSizeBytes`)
        }

        if (userLimitsEphemeralStorageBytes != undefined && userLimitsEphemeralStorageBytes < minimumEphemeralBytes) {
            throw new Error(
                `Resource limits for RFS storage of ${userLimitsEphemeralStorageBytes} is too low to support maxShardSizeBytes value of ${data.maxShardSizeBytes}.
            Increase to at least ${minimumEphemeralBytes} to support the migration or decrease maxShardSizeBytes`)
        }

        // pick the larger of (user, minimum); if neither present fall back to minimum
        const reqBytes = Math.max(userRequestEphemeralStorageBytes ?? 0, minimumEphemeralBytes);
        // ensure limits >= requests; if user set a higher limit, keep it
        const limBytes = Math.max(userLimitsEphemeralStorageBytes ?? 0, reqBytes);

        return deepmerge(
            data,
            {
                resources: {
                    requests: { "ephemeral-storage": Math.ceil(reqBytes / 1024) + "Ki" },
                    limits: { "ephemeral-storage": Math.ceil(limBytes / 1024) + "Ki" },
                },
            },
        );
});

export const KAFKA_CLUSTER_CREATION_CONFIG = z.preprocess(
    (value) => deepmerge(DEFAULT_AUTO_CREATE_KAFKA, (value ?? {}), {arrayMerge: replaceArrayMerge}),
    z.object({
        auth: KAFKA_AUTO_CREATE_AUTH_CONFIG.optional()
            .describe("Workflow-owned Kafka client auth for auto-created Strimzi clusters. "
                + "If omitted, transform-time resolution currently defaults workflow-managed Kafka to "
                + "`scram-sha-512` as the secure-by-default policy. This default is intentionally "
                + "resolved outside the deep-merged Strimzi object defaults so auth policy can change "
                + "without being hidden inside the structural Kafka/NodePool/Topic default merge.")
            .effectiveDefault({
                label: "scram-sha-512",
                value: {type: "scram-sha-512"},
                description: "Omitting this field uses workflow-managed Kafka SCRAM-SHA-512 authentication.",
            }),
        // Intended contract: users provide Strimzi-shaped partial Kafka.spec values and
        // initialization deep-merges them with the baseline defaults above.
        //
        // Current limitation: some workflow templates still render selected nested
        // Strimzi fields explicitly instead of passing the merged subtree through
        // verbatim, because Argo resource-manifest rendering has proven unreliable
        // for certain whole-object injections. That means new Strimzi fields may
        // require workflow-template updates before they can flow end to end.
        //
        // Future direction: move Strimzi resource application onto a rendering/apply
        // path that can preserve full merged subtrees without unpacking them into
        // explicit Argo parameters, restoring better fidelity as Strimzi evolves.
        clusterSpecOverrides: GENERIC_JSON_OBJECT.optional()
            .describe("Optional overrides merged into the generated Strimzi Kafka.spec. " +
                "Workflow-managed fields such as resource names, required listeners, and workflow-owned auth settings may be overwritten by the workflow."),
        nodePoolSpecOverrides: GENERIC_JSON_OBJECT.optional()
            .describe("Optional overrides merged into the generated Strimzi KafkaNodePool.spec. " +
                "Workflow-managed fields such as cluster labels may be overwritten by the workflow."),
        topicSpecOverrides: GENERIC_JSON_OBJECT.optional()
            .describe("Optional overrides merged into generated Strimzi KafkaTopic.spec values for workflow-created topics."),
    }).describe("Workflow-managed Strimzi Kafka cluster creation. Structural defaults for broker config, node pool, and topic settings are deep-merged here, while the auth default is resolved separately during transform-time policy application.")
);

export const KAFKA_CLUSTER_CONFIG = z.union([
    z.object({existing: KAFKA_EXISTING_CLUSTER_CONFIG })
        .describe("Use an existing Kafka cluster by providing connection details."),
    z.object({autoCreate: KAFKA_CLUSTER_CREATION_CONFIG})
        .describe("Auto-create a new Strimzi Kafka cluster with the specified configuration. " +
            "The cluster bootstrap service is available at '<clusterName>-kafka-bootstrap.<namespace>:9092'.")
]).describe("Kafka cluster configuration: either auto-create a new Strimzi cluster or connect to an existing one.");

export const HTTP_AUTH_BASIC = z.object({
    basic: z.object({
        secretName: z.string().regex(K8S_NAMING_PATTERN)
            .describe("Name of a Kubernetes Secret containing 'username' and 'password' keys for HTTP Basic authentication.")
            .uiHint(K8S_NAME_UI_HINT)
            .externalRef({
                ...kubernetesResourceRef({
                    purpose: 'http-basic-auth',
                    displayName: 'HTTP Basic Auth Secret',
                    description: "Kubernetes Secret containing 'username' and 'password' keys for HTTP Basic authentication.",
                    resourceTypes: [CORE_V1_SECRET],
                    matchProfiles: ['http-basic-auth-secret'],
                }),
                create: {
                    label: 'HTTP Basic Auth Secret',
                    fields: [
                        {
                            name: 'secretName',
                            label: 'Secret name',
                            input: 'name',
                            required: true,
                            validationIds: ['k8s-name'],
                        },
                        {
                            name: 'username',
                            label: 'Username',
                            input: 'text',
                            required: true,
                            validationIds: ['non-empty'],
                        },
                        {
                            name: 'password',
                            label: 'Password',
                            input: 'password',
                            required: true,
                            sensitive: true,
                            validationIds: ['non-empty'],
                            confirm: true,
                        },
                    ],
                    output: {
                        kind: 'Secret',
                        type: 'kubernetes.io/basic-auth',
                        stringData: {
                            username: {fromField: 'username'},
                            password: {fromField: 'password'},
                        },
                    },
                    apply: {
                        target: 'scalarName',
                        nameField: 'secretName',
                    },
                },
            })
    })
}).describe("HTTP Basic authentication using credentials from a Kubernetes Secret.");

export const HTTP_AUTH_SIGV4 = z.object({
    sigv4: z.object({
        region: z.string()
            .describe("AWS region for SigV4 request signing (e.g. 'us-east-1')."),
        service: z.string().default("es").optional()
            .describe("AWS service name for SigV4 signing. Use 'es' for Amazon OpenSearch Service or 'aoss' for OpenSearch Serverless."),
    })
}).describe("AWS SigV4 request signing authentication. Uses the pod's IAM role credentials via EKS Pod Identity.");

export const HTTP_AUTH_MTLS = z.object({
    mtls: z.object({
        caCert: z.string()
            .describe("PEM-encoded CA certificate or path to CA certificate file for verifying the server's TLS certificate."),
        clientSecretName: z.string()
            .describe("Name of a Kubernetes TLS Secret containing the client certificate and private key for mutual TLS authentication.")
            .uiHint(K8S_NAME_UI_HINT)
    })
}).describe("Mutual TLS (mTLS) authentication using client certificates.");

export const CLUSTER_VERSION_PATTERN = /^(?:ES [125678]|OS [123]|SOLR [6789])(?:\.[0-9]+)+$/;
export const CLUSTER_VERSION_STRING = z.string().regex(CLUSTER_VERSION_PATTERN)
    .describe("Cluster version string in '<ENGINE> <VERSION>' format. Supported engines: 'ES' (Elasticsearch) versions 1, 2, 5, 6, 7, 8; 'OS' (OpenSearch) versions 1, 2, 3; 'SOLR' (Apache Solr) versions 6, 7, 8, 9. Examples: 'ES 7.10.2', 'OS 2.11.0', 'SOLR 9.7.0', 'SOLR 6.6.0'.")
    .uiHint({
        kind: 'text',
        format: 'cluster-version',
        pattern: CLUSTER_VERSION_PATTERN.source,
        message: "Use '<ENGINE> <VERSION>', such as 'ES 7.10.2', 'OS 2.11.0', or 'SOLR 9.7.0'.",
        examples: ['ES 7.10.2', 'OS 2.11.0', 'SOLR 9.7.0'],
    });

export const CLUSTER_CONFIG = z.object({
    endpoint:  z.string().regex(new RegExp(OPTIONAL_HTTP_ENDPOINT_PATTERN)).default("").optional()
        .describe("HTTP(S) endpoint URL for the cluster (e.g. 'https://my-cluster:9200/'). Leave empty if the cluster is not directly accessible or will be accessed through a proxy.")
        .uiHint(OPTIONAL_HTTP_ENDPOINT_UI_HINT),
    allowInsecure: z.boolean().default(false).optional()
        .describe("When true, disables TLS certificate verification when connecting to the cluster. Use only for development or self-signed certificates."),
    authConfig: z.union([HTTP_AUTH_BASIC, HTTP_AUTH_SIGV4, HTTP_AUTH_MTLS]).optional()
        .describe("Authentication configuration for connecting to the cluster. Supports HTTP Basic (Kubernetes Secret), AWS SigV4, or mutual TLS."),
}).describe("Base connection configuration for an Elasticsearch or OpenSearch cluster.");

export const TARGET_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    endpoint:  z.string().regex(new RegExp(HTTP_ENDPOINT_PATTERN))
        .describe("HTTP(S) endpoint URL for the target cluster (e.g. 'https://target-cluster:9200/'). Required for target clusters.")
        .uiHint(HTTP_ENDPOINT_UI_HINT),
}).describe("Connection configuration for a target OpenSearch cluster. Extends the base cluster config with a required endpoint.");

export const SOURCE_CLUSTER_REPOS_RECORD =
    z.record(z.string(), REPO_CONFIG)
    .uiHint({
        kind: 'record',
        addLabel: 'snapshot repository',
    })
    .describe("Map of snapshot repository names to their backing-store configurations. Keys are the repository names as registered in the source cluster. Each value's repoPathUri scheme determines the backend (s3:// or gs://).");

export const CAPTURE_CONFIG = z.object({
    kafka: z.string().regex(K8S_NAMING_PATTERN).default("default").optional()
        .describe("Label of the Kafka cluster to use for captured traffic. Must match a key in kafkaClusterConfiguration.")
        .uiHint({
            kind: 'reference',
            sourcePath: ['kafkaClusterConfiguration'],
            emptyMeansDefault: 'default',
            message: "Choose one Kafka cluster from kafkaClusterConfiguration.",
        }),
    kafkaTopic: z.string().regex(K8S_NAMING_PATTERN).default("").optional()
        .describe("Kafka topic name for captured traffic. If empty, defaults to the proxy name (the key in the proxies record).")
        .uiHint(K8S_NAME_UI_HINT),
    source: z.string()
        .describe("Name of the source cluster this proxy sits in front of. Must match a key in sourceClusters.")
        .uiHint({
            kind: 'reference',
            sourcePath: ['sourceClusters'],
            message: "Choose one source cluster from sourceClusters.",
        }),
    proxyConfig: USER_PROXY_OPTIONS
        .describe("Configuration for the capture proxy deployment and process options.")
}).describe("Configuration for a single capture proxy instance, including its Kafka topic and source cluster binding.");

export const S3_CAPTURED_TRAFFIC_SOURCE = z.object({
    s3Uri: z.string()
        .regex(/^s3:\/\/[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]\/.+\.proto\.gz$/)
        .describe("S3 URI of a gzipped traffic export produced by kafkaExport.sh. Format must be 's3://BUCKET/PATH/<file>.proto.gz'."),
    awsRegion: z.string()
        .describe("AWS region of the S3 bucket holding the export."),
    endpoint: z.string().regex(OPTIONAL_STORAGE_ENDPOINT_PATTERN).default("").optional()
        .describe("Override the S3 endpoint URL. Supports http://, https://, localstack://, and localstacks:// schemes. " +
            "LocalStack endpoints are automatically resolved to IP addresses during config transformation."),
    kafka: z.string().regex(K8S_NAMING_PATTERN).default("default").optional()
        .describe("Label of the Kafka cluster to load captured traffic into. Must match a key in kafkaClusterConfiguration.")
        .uiHint({
            kind: 'reference',
            sourcePath: ['kafkaClusterConfiguration'],
            emptyMeansDefault: 'default',
            message: "Choose one Kafka cluster from kafkaClusterConfiguration.",
        }),
    kafkaTopic: z.string().regex(K8S_NAMING_PATTERN).default("").optional()
        .describe("Kafka topic name to load captured traffic into. If empty, defaults to the s3Source name (the key in the s3Sources record)."),
    sourceLabel: z.string()
        .describe("Label of the source cluster this dump was originally captured from. " +
            "Used for resource labeling. Does NOT need to match a sourceClusters key " +
            "(the original source may be long gone by the time the dump is replayed)."),
}).describe("Configuration for a one-time load of a previously captured traffic archive from S3 onto a Kafka topic. " +
    "When set, the workflow does NOT stand up a CaptureProxy — replay reads from the loaded topic directly. " +
    "The loader runs once per CapturedTraffic resource: re-runs are blocked by the resource lifecycle, " +
    "and changing the s3Uri requires deleting the CapturedTraffic resource and re-running the workflow.");

export const SNAPSHOT_MIGRATION_FILTER = z.object({
    source: z.string()
        .describe("Name of the source cluster. Must match a key in sourceClusters.")
        .uiHint({
            kind: 'reference',
            sourcePath: ['sourceClusters'],
            message: "Choose one source cluster from sourceClusters.",
        }),
    snapshot: z.string()
        .describe("Name of the snapshot. Must match a key in the source cluster's snapshotInfo.snapshots.")
        .uiHint({
            kind: 'reference',
            sourcePathTemplate: [
                'sourceClusters',
                {valueFrom: ['..', 'source']},
                'snapshotInfo',
                'snapshots',
            ],
            message: "Choose a snapshot defined under the selected source cluster's snapshotInfo.snapshots.",
        })
}).describe("Reference to a specific snapshot from a specific source cluster, used to express dependencies.");

export const REPLAYER_CONFIG = z.object({
    fromCapturedTraffic: z.string()
        .describe("Name of the captured-traffic source to replay from. Must match a key in either traffic.proxies (live capture) or traffic.s3Sources (pre-recorded S3 dump).")
        .uiHint({
            kind: 'reference',
            sourcePaths: [
                ['traffic', 'proxies'],
                ['traffic', 's3Sources'],
            ],
            allowCustom: true,
            message: "Choose one captured-traffic source from traffic.proxies or traffic.s3Sources.",
        }),
    toTarget: z.string()
        .describe("Name of the target cluster to replay traffic to. Must match a key in targetClusters.")
        .uiHint({
            kind: 'reference',
            sourcePath: ['targetClusters'],
            message: "Choose one target cluster from targetClusters.",
        }),
    dependsOnSnapshotMigrations: z.array(SNAPSHOT_MIGRATION_FILTER).default([]).optional()
        .describe("List of snapshot migrations that must complete before this replayer starts. Ensures data consistency when replaying traffic that depends on backfilled data.")
        .essential(),
    replayerConfig: USER_REPLAYER_OPTIONS.optional()
        .describe("Optional replayer configuration overrides. If omitted, replayer runs with schema defaults.")
}).describe("Configuration for a single traffic replayer instance, binding a captured-traffic source (live proxy or S3 dump) to a target cluster.");

export const TRAFFIC_CONFIG = z.object({
    proxies: z.record(z.string().regex(K8S_NAMING_PATTERN), CAPTURE_CONFIG).default({}).optional()
        .describe("Map of proxy names to their live-capture configurations. Keys become the Kubernetes Service names and must be valid DNS labels.")
        .uiHint({
            kind: 'record',
            addLabel: 'capture proxy',
            keyFormat: 'k8s-name',
            keyPattern: K8S_NAMING_PATTERN.source,
            message: "Use a valid Kubernetes DNS name for the capture proxy.",
        }),
    s3Sources: z.record(z.string().regex(K8S_NAMING_PATTERN), S3_CAPTURED_TRAFFIC_SOURCE).default({}).optional()
        .describe("[Expert] Optional map of pre-recorded traffic source names to their S3 archive configurations. " +
            "Each entry triggers a one-time load from S3 onto a Kafka topic; no live capture proxy is created. " +
            "Keys must not collide with traffic.proxies keys (replayer.fromCapturedTraffic resolves across both maps).")
        .uiHint({
            kind: 'record',
            addLabel: 'optional S3 archive source (no capture proxy)',
            keyFormat: 'k8s-name',
            keyPattern: K8S_NAMING_PATTERN.source,
            message: "Use a valid Kubernetes DNS name for the optional S3 archive source.",
        }),
    replayers: z.record(z.string().regex(K8S_NAMING_PATTERN), REPLAYER_CONFIG).default({}).optional()
        .describe("Map of replayer names to their replay configurations. Each replayer consumes from a Kafka topic and replays to a target cluster.")
        .uiHint({
            kind: 'record',
            addLabel: 'traffic replay',
            keyFormat: 'k8s-name',
            keyPattern: K8S_NAMING_PATTERN.source,
            message: "Replay names become Kubernetes TrafficReplay resource names and must use lower-case RFC 1123 syntax.",
        })
}).superRefine((data, ctx) => {
    const proxies = data.proxies ?? {};
    const s3Sources = data.s3Sources ?? {};
    for (const name of Object.keys(s3Sources)) {
        if (name in proxies) {
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: `Name '${name}' is used in both traffic.proxies and traffic.s3Sources. Each captured-traffic source must have a unique name.`,
                path: ['s3Sources', name]
            });
        }
    }
    // Two captured-traffic sources cannot land in the same Kafka topic on the
    // same Kafka cluster. The effective topic is `kafkaTopic ?? sourceName`,
    // so name collisions across sources, explicit-topic collisions, and any
    // mix that maps to the same (cluster, topic) tuple all need to be caught.
    // Without this, two producers would share one topic — any replayer reading
    // that topic would interleave records from both, with no way to tell them
    // apart, and `kafkaImport.sh`-style reloads would write into a topic the
    // proxy is also feeding.
    type Origin = { kind: 'proxy' | 's3Source'; name: string };
    const claims = new Map<string, Origin>();
    const recordClaim = (cluster: string, topic: string, origin: Origin, path: (string | number)[]) => {
        const key = `${cluster}\0${topic}`;
        const existing = claims.get(key);
        if (existing) {
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: `traffic.${origin.kind}s['${origin.name}'] targets kafka cluster '${cluster}' topic '${topic}', which is already claimed by traffic.${existing.kind}s['${existing.name}']. Each (kafka cluster, topic) tuple must have at most one producer.`,
                path
            });
        } else {
            claims.set(key, origin);
        }
    };
    for (const [name, p] of Object.entries(proxies)) {
        const cluster = p.kafka ?? "default";
        const topic = (p.kafkaTopic && p.kafkaTopic !== "") ? p.kafkaTopic : name;
        recordClaim(cluster, topic, { kind: 'proxy', name }, ['proxies', name, 'kafkaTopic']);
    }
    for (const [name, s3] of Object.entries(s3Sources)) {
        const cluster = s3.kafka ?? "default";
        const topic = (s3.kafkaTopic && s3.kafkaTopic !== "") ? s3.kafkaTopic : name;
        recordClaim(cluster, topic, { kind: 's3Source', name }, ['s3Sources', name, 'kafkaTopic']);
    }
    for (const [name, rc] of Object.entries(data.replayers ?? {})) {
        const inProxies = rc.fromCapturedTraffic in proxies;
        const inS3 = rc.fromCapturedTraffic in s3Sources;
        if (!inProxies && !inS3) {
            const available = [...Object.keys(proxies), ...Object.keys(s3Sources)];
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: `Replayer '${name}' references unknown captured-traffic source '${rc.fromCapturedTraffic}'. Available (proxies + s3Sources): ${available.join(', ')}`,
                path: ['replayers', name, 'fromCapturedTraffic']
            });
        }
    }
});

export const EXTERNALLY_MANAGED_SNAPSHOT = z.object({
    externallyManagedSnapshotName: z.string()
        .describe("Name of a pre-existing snapshot in the source cluster's repository. The workflow will use this snapshot directly without creating a new one.")
}).describe("Reference to a snapshot that was created outside of this migration workflow.");

export const GENERATE_SNAPSHOT = z.object({
    createSnapshotConfig: USER_CREATE_SNAPSHOT_OPTIONS
        .describe("Configuration for creating a new snapshot of the source cluster."),
}).describe("Configuration to create a new snapshot of the source cluster as part of the migration workflow.");

export const SNAPSHOT_NAME_CONFIG = z.union([
    EXTERNALLY_MANAGED_SNAPSHOT, GENERATE_SNAPSHOT
]).describe("Snapshot source: either reference an existing snapshot or configure creation of a new one.");

export const NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG = z.object({
    config: SNAPSHOT_NAME_CONFIG
        .describe("Snapshot configuration: either an externally managed snapshot name or settings to create a new snapshot."),
    repoName: z.string()
        .describe("Name of the S3 snapshot repository. Must match a key in the source cluster's snapshotInfo.repos.")
}).describe("A snapshot configuration bound to a specific repository.");

export const SNAPSHOT_CONFIGS_MAP = z.record(
    z.string(),
    NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG
).uiHint({
    kind: 'record',
    addLabel: 'source snapshot',
}).describe("Map of snapshot names to their configurations. Keys are used as labels and in snapshot name generation.");

export const SNAPSHOT_INFO = z.object({
    repos: SOURCE_CLUSTER_REPOS_RECORD.optional()
        .describe("S3 snapshot repositories registered with the source cluster."),
    snapshots: SNAPSHOT_CONFIGS_MAP
        .describe("Snapshots to use or create for this source cluster."),
    serializeSnapshotCreation: z.boolean().optional()
        .describe("Controls whether snapshot creations for this source run one-at-a-time or in parallel. " +
            "When true, all snapshot creations share a single semaphore so only one runs at any given time; " +
            "when false, each snapshot can run in parallel with others for this source. " +
            "When omitted, defaults to true for legacy sources (ES 1-7, OS 1) and false for modern sources (ES 8+, OS 2+). " +
            "Set explicitly to override the version-based default. " +
            "Common reason to force true on a modern source: the cluster only supports one snapshot at a time for the indices being captured " +
            "(for example, OpenSearch UltraWarm indices, which only allow a single index per snapshot and cannot be snapshotted concurrently).")
}).describe("Snapshot repository and snapshot configuration for a source cluster.");

const AWS_MANAGED_ENDPOINT_PATTERN = /(?:\.es\.amazonaws\.com|\.aos\.[a-z0-9-]+\.on\.aws)(?::\d+)?(?:\/)?$/i;

export const SOURCE_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    version: CLUSTER_VERSION_STRING,
    snapshotInfo: SNAPSHOT_INFO.optional()
        .essential()
        .describe("Snapshot repository and snapshot configurations for this source cluster. Required if any snapshot-based migrations reference this source.")
}).describe("Connection and snapshot configuration for a source cluster.").superRefine((data, ctx) => {
    const repos = data.snapshotInfo?.repos;
    const snapshots = data.snapshotInfo?.snapshots ?? {};
    for (const [snapName, snapConfig] of Object.entries(snapshots)) {
        const repoName = snapConfig.repoName;
        if (repoName) {
            if (!repos) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `Snapshot '${snapName}' references repoName '${repoName}' but no repos are defined`,
                    path: ['snapshotInfo', 'snapshots', snapName, 'repoName']
                });
            } else if (!(repoName in repos)) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `Snapshot '${snapName}' references unknown repoName '${repoName}'. Available: ${Object.keys(repos).join(', ')}`,
                    path: ['snapshotInfo', 'snapshots', snapName, 'repoName']
                });
            }
        }
    }

    // AWS managed clusters require SigV4 auth when triggering snapshot creation
    if (data.endpoint && AWS_MANAGED_ENDPOINT_PATTERN.test(data.endpoint)) {
        const hasCreateSnapshot = Object.values(snapshots).some(s => "createSnapshotConfig" in s.config);
        if (hasCreateSnapshot && (!data.authConfig || !HTTP_AUTH_SIGV4.safeParse(data.authConfig).success)) {
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: "SigV4 auth is required for Amazon OpenSearch domains when the workflow creates snapshot",
                path: ['authConfig']
            });
        }
    }

    // SigV4 auth + createSnapshotConfig requires s3RoleArn on the referenced S3 repo.
    // (GCS repos are not affected — they authenticate via the cluster's GCS keystore / Workload Identity.)
    if (data.authConfig && HTTP_AUTH_SIGV4.safeParse(data.authConfig).success) {
        for (const [snapName, snapConfig] of Object.entries(snapshots)) {
            if ("createSnapshotConfig" in snapConfig.config) {
                const repo = repos?.[snapConfig.repoName];
                if (repo && repo.repoPathUri.startsWith("s3://") && !repo.s3RoleArn) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `Snapshot '${snapName}' uses SigV4 auth with createSnapshotConfig but repo '${snapConfig.repoName}' is missing s3RoleArn`,
                        path: ['snapshotInfo', 'repos', snapConfig.repoName, 's3RoleArn']
                    });
                }
            }
        }
    }
});

export const NORMALIZED_COMPLETE_SNAPSHOT_CONFIG = z.object({
    snapshotName: z.string()
        .describe("Resolved name of the snapshot to use for migration.")
}).describe("A fully resolved snapshot configuration with a concrete snapshot name.");

export const USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG = z.object({
    label: z.string().regex(/^[a-zA-Z][a-zA-Z0-9]*/).default("").optional()
        .describe("Unique label for this migration within its snapshot group. Auto-generated as 'migration-<index>' if not specified. Must start with a letter and contain only alphanumeric characters."),
    metadataMigrationConfig: USER_METADATA_OPTIONS.optional()
        .describe("Configuration for migrating index metadata (mappings, settings, templates) from the snapshot to the target. Omit to skip metadata migration."),
    documentBackfillConfig: USER_RFS_OPTIONS.optional()
        .describe("Configuration for backfilling documents from the snapshot to the target using Reindex From Snapshot. Omit to skip document backfill."),
}).describe("Configuration for a single migration pass from a snapshot. At least one of metadataMigrationConfig or documentBackfillConfig must be provided.").refine(data =>
        data.metadataMigrationConfig !== undefined ||
        data.documentBackfillConfig !== undefined,
    {message: "At least one of metadataMigrationConfig or documentBackfillConfig must be provided"});

export const SNAPSHOT_MIGRATION_CONFIG_ARRAY =
    z.array(USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG)
    .min(1)
    .uiHint({kind: 'array', addLabel: 'migration pass'})
    .describe("List of migrations to execute for a single snapshot. " +
        " Each migration must configure metadata migration, document backfill, or both." +
        " These migrations will execute concurrently as dependent snapshots finish.");

export const PER_SNAPSHOT_MIGRATION_CONFIG_RECORD =
    z.record(z.string().regex(/^[a-zA-Z][a-zA-Z0-9]*/),
        SNAPSHOT_MIGRATION_CONFIG_ARRAY)
    .uiHint({
        kind: 'record',
        addLabel: 'snapshot name',
        keyPattern: '^[a-zA-Z][a-zA-Z0-9]*',
        message: "Use a snapshot name defined under the selected source cluster's snapshotInfo.snapshots.",
    })
    .describe("Map of snapshot names to their migration configurations. Keys must match snapshot names defined in the source cluster's snapshotInfo.snapshots.");

export const NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG = z.object({
    skipApprovals : z.boolean().default(false).optional()
        .describe("When true, skips all manual approval gates for migrations in this configuration block."),
    fromSource: z.string()
        .describe("Label of the source cluster to migrate from. Must match a key in sourceClusters.")
        .uiHint({
            kind: 'reference',
            sourcePath: ['sourceClusters'],
            message: "Choose one source cluster from sourceClusters.",
        }),
    toTarget: z.string()
        .describe("Label of the target cluster to migrate to. Must match a key in targetClusters.")
        .uiHint({
            kind: 'reference',
            sourcePath: ['targetClusters'],
            message: "Choose one target cluster from targetClusters.",
        }),
    perSnapshotConfig: PER_SNAPSHOT_MIGRATION_CONFIG_RECORD
        .describe("Per-snapshot migration configurations. Each entry maps a snapshot name to one or more migration passes (metadata + document backfill).")
        .essential(),
}).describe("A snapshot-based migration configuration binding a source cluster to a target cluster with per-snapshot migration settings.").superRefine((data, ctx) => {
    if (!data.perSnapshotConfig) return;
    for (const [snapName, migrations] of Object.entries(data.perSnapshotConfig)) {
        const labels = migrations.map(m => m.label).filter(Boolean);
        if (labels.length !== new Set(labels).size) {
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: `Duplicate labels in perSnapshotConfig['${snapName}']`,
                path: ['perSnapshotConfig', snapName]
            });
        }
    }
});

export const KAFKA_CLUSTERS_MAP = z.record(z.string().regex(K8S_NAMING_PATTERN), KAFKA_CLUSTER_CONFIG)
    .describe("Map of Kafka cluster names to their configurations. Keys become Kubernetes resource names and must be valid DNS labels. If empty and proxies are configured, a 'default' auto-created cluster is used.")
    .uiHint({
        kind: 'record',
        addLabel: 'Kafka cluster',
        keyFormat: 'k8s-name',
        keyPattern: K8S_NAMING_PATTERN.source,
        message: "Use a valid Kubernetes DNS name for the Kafka cluster.",
    });
export const SOURCE_CLUSTERS_MAP = z.record(z.string(), SOURCE_CLUSTER_CONFIG)
    .describe("Map of source cluster names to their configurations. Keys are used as labels throughout the migration workflow.")
    .uiHint({
        kind: 'record',
        addLabel: 'source cluster',
    });
export const TARGET_CLUSTERS_MAP = z.record(z.string(), TARGET_CLUSTER_CONFIG)
    .describe("Map of target cluster names to their configurations. Keys are used as labels and must be referenced by snapshotMigrationConfigs and traffic replayers.")
    .uiHint({
        kind: 'record',
        addLabel: 'target cluster',
    });

export const OVERALL_MIGRATION_CONFIG = //validateOptionalDefaultConsistency
(
    z.object({
        skipApprovals : z.boolean().default(false).optional()
            .describe("Global flag to skip all manual approval gates across the entire migration. When true, overrides all per-component skipApproval settings."),
        kafkaClusterConfiguration: KAFKA_CLUSTERS_MAP.default({}).optional()
            .describe("Kafka cluster configurations. If empty and traffic capture is configured, a default ephemeral Kafka cluster is auto-created for each referenced cluster label. " +
                "Each entry defines a Kafka cluster (auto-created or external) referenced by proxies via 'kafka'."),
        sourceClusters: SOURCE_CLUSTERS_MAP
            .describe("Source Elasticsearch or OpenSearch clusters to migrate from."),
        targetClusters: TARGET_CLUSTERS_MAP
            .describe("Target OpenSearch clusters to migrate to."),
        snapshotMigrationConfigs: z.array(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG)
            .describe("List of snapshot-based migration configurations. Each entry binds a source cluster to a target cluster and defines which snapshots to migrate with what settings.")
            .uiHint({
                kind: 'array',
                addLabel: 'snapshot migration',
            }),
        traffic: TRAFFIC_CONFIG
            .describe("Traffic capture and replay configuration. Proxies capture live traffic from source clusters to Kafka, and replayers consume from Kafka to replay against target clusters. " +
                "All top-level items are independent, but replayers can declare dependencies on snapshot migrations to ensure data consistency.")
            .optional()
    }).describe("Top-level migration configuration defining source clusters, target clusters, snapshot migrations, and optional traffic capture/replay.").superRefine((data, ctx) => {
        const duplicateClusterNames = Object.keys(data.sourceClusters)
            .filter(name => name in data.targetClusters);
        const sourcesRequiringEndpoint = new Map<string, Set<string>>();
        const addSourceEndpointRequirement = (sourceName: string, reason: string) => {
            if (!sourceName || !(sourceName in data.sourceClusters)) {
                return;
            }
            if (!sourcesRequiringEndpoint.has(sourceName)) {
                sourcesRequiringEndpoint.set(sourceName, new Set());
            }
            sourcesRequiringEndpoint.get(sourceName)!.add(reason);
        };
        for (const name of duplicateClusterNames) {
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: `Cluster name '${name}' is used in both sourceClusters and targetClusters. Source and target cluster names must be unique.`,
                path: ['targetClusters', name]
            });
        }

        for (let i = 0; i < data.snapshotMigrationConfigs.length; i++) {
            const mc = data.snapshotMigrationConfigs[i];

            if (!(mc.fromSource in data.sourceClusters)) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `snapshotMigrationConfigs[${i}] references unknown source '${mc.fromSource}'. Available: ${Object.keys(data.sourceClusters).join(', ')}`,
                    path: ['snapshotMigrationConfigs', i, 'fromSource']
                });
            } else {
                addSourceEndpointRequirement(mc.fromSource, `snapshotMigrationConfigs[${i}]`);
            }

            if (!(mc.toTarget in data.targetClusters)) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `snapshotMigrationConfigs[${i}] references unknown target '${mc.toTarget}'. Available: ${Object.keys(data.targetClusters).join(', ')}`,
                    path: ['snapshotMigrationConfigs', i, 'toTarget']
                });
            }

            if (mc.perSnapshotConfig) {
                const sourceCluster = data.sourceClusters[mc.fromSource];
                const availableSnapshots = sourceCluster?.snapshotInfo?.snapshots ?? {};
                for (const snapName of Object.keys(mc.perSnapshotConfig)) {
                    if (!(snapName in availableSnapshots)) {
                        const available = Object.keys(availableSnapshots);
                        ctx.addIssue({
                            code: z.ZodIssueCode.custom,
                            message: `perSnapshotConfig references unknown snapshot '${snapName}' in source '${mc.fromSource}'. ` +
                                `Define sourceClusters.${mc.fromSource}.snapshotInfo.snapshots.${snapName}, ` +
                                (available.length
                                    ? `rename this entry to one of: ${available.join(', ')}, `
                                    : "define at least one source snapshot, ") +
                                "or remove this perSnapshotConfig entry.",
                            path: ['snapshotMigrationConfigs', i, 'perSnapshotConfig', snapName]
                        });
                    }
                }
            }
        }

        if (data.traffic) {
            const proxies = data.traffic.proxies ?? {};
            const s3Sources = data.traffic.s3Sources ?? {};
            const kafkaClusters = data.kafkaClusterConfiguration ?? {};
            for (const [proxyName, proxyConfig] of Object.entries(proxies)) {
                if (!(proxyConfig.source in data.sourceClusters)) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `Proxy '${proxyName}' references unknown source '${proxyConfig.source}'. Available: ${Object.keys(data.sourceClusters).join(', ')}`,
                        path: ['traffic', 'proxies', proxyName, 'source']
                    });
                } else {
                    addSourceEndpointRequirement(proxyConfig.source, `traffic.proxies.${proxyName}`);
                }
                const kafkaRef = proxyConfig.kafka ?? 'default';
                if (Object.keys(kafkaClusters).length > 0 && !(kafkaRef in kafkaClusters)) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `Proxy '${proxyName}' references unknown kafka cluster '${kafkaRef}'. Available: ${Object.keys(kafkaClusters).join(', ')}. Set traffic.proxies.${proxyName}.kafka or add kafkaClusterConfiguration.${kafkaRef}.`,
                        path: ['traffic', 'proxies', proxyName, 'kafka']
                    });
                }
            }
            for (const [s3Name, s3Config] of Object.entries(s3Sources)) {
                const kafkaRef = s3Config.kafka ?? 'default';
                if (Object.keys(kafkaClusters).length > 0 && !(kafkaRef in kafkaClusters)) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `s3Source '${s3Name}' references unknown kafka cluster '${kafkaRef}'. Available: ${Object.keys(kafkaClusters).join(', ')}. Set traffic.s3Sources.${s3Name}.kafka or add kafkaClusterConfiguration.${kafkaRef}.`,
                        path: ['traffic', 's3Sources', s3Name, 'kafka']
                    });
                }
            }

            for (const [replayerName, rc] of Object.entries(data.traffic.replayers ?? {})) {
                if (!(rc.toTarget in data.targetClusters)) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `Replayer '${replayerName}' references unknown target '${rc.toTarget}'. Available: ${Object.keys(data.targetClusters).join(', ')}`,
                        path: ['traffic', 'replayers', replayerName, 'toTarget']
                    });
                }

                for (let j = 0; j < (rc.dependsOnSnapshotMigrations?.length ?? 0); j++) {
                    const dep = rc.dependsOnSnapshotMigrations![j];
                    if (!(dep.source in data.sourceClusters)) {
                        ctx.addIssue({
                            code: z.ZodIssueCode.custom,
                            message: `Replayer '${replayerName}' dependsOnSnapshotMigrations[${j}] references unknown source '${dep.source}'. Available: ${Object.keys(data.sourceClusters).join(', ')}`,
                            path: ['traffic', 'replayers', replayerName, 'dependsOnSnapshotMigrations', j, 'source']
                        });
                    }
                }

                // When the target has sigv4 or basic auth, the workflow auto-derives the replayer's auth
                // at deploy time. Setting removeAuthHeader alongside that causes a dual-auth startup crash.
                const targetCluster = data.targetClusters[rc.toTarget];
                if (targetCluster && rc.replayerConfig && rc.replayerConfig.removeAuthHeader === true) {
                    const targetHasSigv4 = targetCluster.authConfig && HTTP_AUTH_SIGV4.safeParse(targetCluster.authConfig).success;
                    const targetHasBasicAuth = targetCluster.authConfig && HTTP_AUTH_BASIC.safeParse(targetCluster.authConfig).success;
                    if (targetHasSigv4 || targetHasBasicAuth) {
                        const targetAuthType = targetHasSigv4 ? 'sigv4' : 'basic';
                        ctx.addIssue({
                            code: z.ZodIssueCode.custom,
                            message: `Replayer '${replayerName}' sets 'removeAuthHeader' but target '${rc.toTarget}' uses ${targetAuthType} auth. ` +
                                `The replayer cannot use both — the target's auth is applied automatically. Remove 'removeAuthHeader' from replayerConfig.`,
                            path: ['traffic', 'replayers', replayerName, 'replayerConfig', 'removeAuthHeader']
                        });
                    }
                }
            }
        }

        for (const [sourceName, reasons] of sourcesRequiringEndpoint.entries()) {
            const source = data.sourceClusters[sourceName];
            if (typeof source.endpoint === 'string' && source.endpoint.trim()) {
                continue;
            }
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: `Source endpoint is required because ${Array.from(reasons).join(', ')} references this source.`,
                path: ['sourceClusters', sourceName, 'endpoint']
            });
        }
    })
);
