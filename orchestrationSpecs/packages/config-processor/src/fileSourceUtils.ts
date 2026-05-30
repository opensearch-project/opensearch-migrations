import {
    ARGO_FILE_SOURCE_VOLUME,
    ARGO_FILE_SOURCE_VOLUME_MOUNT,
    FILE_REF,
    TRANSFORM_CONTEXT_VALUE_DIRECTORY,
} from "@opensearch-migrations/schemas";
import {createHash} from "crypto";
import {z} from "zod";

type FileRef = z.infer<typeof FILE_REF>;
type TransformContextValueDirectory = z.infer<typeof TRANSFORM_CONTEXT_VALUE_DIRECTORY>;
type ArgoFileSourceVolume = z.infer<typeof ARGO_FILE_SOURCE_VOLUME>;
type ArgoFileSourceVolumeMount = z.infer<typeof ARGO_FILE_SOURCE_VOLUME_MOUNT>;

export const FILE_SOURCE_VOLUME_FIELD = "fileSourceVolumes";
export const FILE_SOURCE_VOLUME_MOUNT_FIELD = "fileSourceVolumeMounts";
export const FILE_SOURCE_RUNTIME_FIELDS = [
    FILE_SOURCE_VOLUME_FIELD,
    FILE_SOURCE_VOLUME_MOUNT_FIELD,
] as const;

export type ResolvedFileSourceFields = {
    [FILE_SOURCE_VOLUME_FIELD]: ArgoFileSourceVolume[];
    [FILE_SOURCE_VOLUME_MOUNT_FIELD]: ArgoFileSourceVolumeMount[];
};

export type FileSourceTraceRef =
    | {configMap: {name: unknown}; paths: string[]}
    | {image: {reference: unknown; pullPolicy: unknown}; paths: string[]};

const FILE_SOURCES_MOUNT_PATH = "/file-sources";

function sanitizeK8sNameSegment(value: string) {
    const sanitized = value.toLowerCase()
        .replace(/[^a-z0-9-]+/g, "-")
        .replace(/^-+|-+$/g, "")
        .slice(0, 40)
        .replace(/-+$/g, "");
    return sanitized || "source";
}

function stableShortHash(value: string) {
    return createHash("sha256").update(value).digest("hex").slice(0, 12);
}

function joinRuntimePath(root: string, relativePath: string | undefined) {
    return relativePath ? `${root}/${relativePath}` : root;
}

function fileSourceIdentity(source: FileRef | TransformContextValueDirectory) {
    return "configMap" in source
        ? `configMap:${source.configMap}`
        : `image:${source.image}:${source.pullPolicy ?? "IfNotPresent"}`;
}

function fileSourceMountLabel(source: FileRef | TransformContextValueDirectory) {
    return "configMap" in source
        ? `configmap-${sanitizeK8sNameSegment(source.configMap)}`
        : "image";
}

export class FileSourceRegistry {
    private readonly entries = new Map<string, {
        volume: ArgoFileSourceVolume;
        volumeMount: ArgoFileSourceVolumeMount;
        mountPath: string;
    }>();

    resolveFileRef(fileRef: FileRef): string {
        return joinRuntimePath(this.registerSource(fileRef), fileRef.path);
    }

    resolveDirectory(directory: TransformContextValueDirectory): string {
        return joinRuntimePath(this.registerSource(directory), "path" in directory ? directory.path : undefined);
    }

    get fileSourceVolumes() {
        return [...this.entries.values()].map(e => e.volume);
    }

    get fileSourceVolumeMounts() {
        return [...this.entries.values()].map(e => e.volumeMount);
    }

    get resolvedFields(): ResolvedFileSourceFields {
        return {
            [FILE_SOURCE_VOLUME_FIELD]: this.fileSourceVolumes,
            [FILE_SOURCE_VOLUME_MOUNT_FIELD]: this.fileSourceVolumeMounts,
        };
    }

    private registerSource(source: FileRef | TransformContextValueDirectory): string {
        const identity = fileSourceIdentity(source);
        const existing = this.entries.get(identity);
        if (existing !== undefined) {
            return existing.mountPath;
        }

        const hash = stableShortHash(identity);
        const volumeName = `file-source-${hash}`;
        const mountPath = `${FILE_SOURCES_MOUNT_PATH}/${fileSourceMountLabel(source)}-${hash}`;
        const volume: ArgoFileSourceVolume = "configMap" in source
            ? {
                name: volumeName,
                configMap: {name: source.configMap}
            }
            : {
                name: volumeName,
                image: {
                    reference: source.image,
                    pullPolicy: source.pullPolicy ?? "IfNotPresent"
                }
            };
        const volumeMount: ArgoFileSourceVolumeMount = {
            name: volumeName,
            mountPath,
            readOnly: true
        };

        this.entries.set(identity, {volume, volumeMount, mountPath});
        return mountPath;
    }
}

function collectStrings(value: unknown): string[] {
    if (typeof value === "string") {
        return [value];
    }
    if (Array.isArray(value)) {
        return value.flatMap(collectStrings);
    }
    if (typeof value === "object" && value !== null) {
        return Object.values(value).flatMap(collectStrings);
    }
    return [];
}

function omitFields(source: Record<string, unknown>, fields: readonly string[]): Record<string, unknown> {
    const result = {...source};
    for (const field of fields) {
        delete result[field];
    }
    return result;
}

export function fileSourceRefsForTrace(omittedFields: Record<string, unknown>): FileSourceTraceRef[] {
    const volumes = Array.isArray(omittedFields[FILE_SOURCE_VOLUME_FIELD])
        ? omittedFields[FILE_SOURCE_VOLUME_FIELD].filter((v): v is Record<string, unknown> =>
            typeof v === "object" && v !== null)
        : [];
    const mounts = Array.isArray(omittedFields[FILE_SOURCE_VOLUME_MOUNT_FIELD])
        ? omittedFields[FILE_SOURCE_VOLUME_MOUNT_FIELD].filter((v): v is Record<string, unknown> =>
            typeof v === "object" && v !== null)
        : [];
    if (volumes.length === 0 || mounts.length === 0) {
        return [];
    }

    const volumeByName = new Map(volumes
        .filter(volume => typeof volume.name === "string")
        .map(volume => [volume.name as string, volume]));
    const materializedPaths = collectStrings(omitFields(omittedFields, FILE_SOURCE_RUNTIME_FIELDS));

    return mounts.flatMap((mount): FileSourceTraceRef[] => {
        if (typeof mount.name !== "string" || typeof mount.mountPath !== "string") {
            return [];
        }
        const mountPath = mount.mountPath;
        const volume = volumeByName.get(mount.name);
        if (volume === undefined) {
            return [];
        }
        const paths = materializedPaths
            .filter(path => path === mountPath || path.startsWith(`${mountPath}/`))
            .map(path => path === mountPath ? "." : path.slice(mountPath.length + 1))
            .sort();
        if (paths.length === 0) {
            return [];
        }

        if (typeof volume.configMap === "object" && volume.configMap !== null) {
            const configMap = volume.configMap as Record<string, unknown>;
            return [{
                configMap: {name: configMap.name},
                paths,
            }];
        }
        if (typeof volume.image === "object" && volume.image !== null) {
            const image = volume.image as Record<string, unknown>;
            return [{
                image: {
                    reference: image.reference,
                    pullPolicy: image.pullPolicy,
                },
                paths,
            }];
        }
        return [];
    });
}
