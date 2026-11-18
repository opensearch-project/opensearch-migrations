import {
    KubeConfig,
    KubernetesObjectApi,
} from "@kubernetes/client-node";
import type {KubernetesObject} from "@kubernetes/client-node";
import type {
    SubmissionAdmissionClient,
} from "./submissionPreflight";

function errorStatus(error: unknown): number | undefined {
    if (!error || typeof error !== "object") {
        return undefined;
    }
    const candidate = error as {
        code?: unknown;
        status?: unknown;
        statusCode?: unknown;
        response?: {status?: unknown; statusCode?: unknown};
    };
    for (const value of [
        candidate.code,
        candidate.status,
        candidate.statusCode,
        candidate.response?.status,
        candidate.response?.statusCode,
    ]) {
        if (typeof value === "number") {
            return value;
        }
    }
    return undefined;
}

export function createKubernetesSubmissionAdmissionClient(): SubmissionAdmissionClient {
    const kubeConfig = new KubeConfig();
    kubeConfig.loadFromDefault();
    const api = KubernetesObjectApi.makeApiClient(kubeConfig);

    return {
        async read(resource, namespace) {
            const resourceNamespace = namespace
                ?? (
                    typeof resource.manifest.metadata.namespace === "string"
                        ? resource.manifest.metadata.namespace
                        : undefined
                );
            const header: {
                apiVersion: string;
                kind: string;
                metadata: {name: string; namespace?: string};
            } = {
                apiVersion: resource.manifest.apiVersion,
                kind: resource.manifest.kind,
                metadata: {
                    name: resource.manifest.metadata.name,
                    ...(resourceNamespace
                        ? {namespace: resourceNamespace}
                        : {}),
                },
            };
            try {
                return await api.read(header) as
                    unknown as Record<string, any>;
            } catch (error) {
                if (errorStatus(error) === 404) {
                    return undefined;
                }
                throw error;
            }
        },
        async dryRun(candidate, existing) {
            const object = candidate as unknown as KubernetesObject;
            if (existing) {
                await api.replace(object, undefined, "All");
            } else {
                await api.create(object, undefined, "All");
            }
        },
    };
}
