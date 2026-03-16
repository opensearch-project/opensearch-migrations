const LEGACY_VERSION_REGEX = /^(?:ES [1-7]|OS 1)(?:\.[0-9]+)*$/;

export function isLegacyVersion(sourceVersion: string): boolean {
    return LEGACY_VERSION_REGEX.test(sourceVersion);
}

export function generateSemaphoreKey(sourceVersion: string, sourceName: string, snapshotName: string): string {
    if (isLegacyVersion(sourceVersion)) {
        return `snapshot-legacy-${sourceName}`;
    }
    return `snapshot-modern-${sourceName}-${snapshotName}`;
}
