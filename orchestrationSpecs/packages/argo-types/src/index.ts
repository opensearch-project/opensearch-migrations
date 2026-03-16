/* Generated Argo Workflows type definitions — do not edit manually, run `npm run rebuild` */
// Run `npm run rebuild` with a cluster that has Argo Workflows installed to regenerate.

/** Version of the Argo Workflows operator this file was generated from. */
export const GENERATED_FROM_VERSION = "unknown — run npm run rebuild";

export interface Workflow {
    apiVersion?: string;
    kind?: string;
    metadata?: Record<string, unknown>;
    spec: Record<string, unknown>;
    status?: Record<string, unknown>;
}

export interface WorkflowTemplate {
    apiVersion?: string;
    kind?: string;
    metadata?: Record<string, unknown>;
    spec: Record<string, unknown>;
}

export interface CronWorkflow {
    apiVersion?: string;
    kind?: string;
    metadata?: Record<string, unknown>;
    spec: Record<string, unknown>;
    status?: Record<string, unknown>;
}
