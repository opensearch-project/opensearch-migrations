/* Generated Argo Workflows type definitions — do not edit manually, run `npm run rebuild` */
// Run `npm run rebuild` with a cluster that has Argo Workflows installed to regenerate.

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
