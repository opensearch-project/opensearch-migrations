const LEGACY_VERSION_REGEX = /^(?:ES [1-7]|OS 1)(?:\.[0-9]+)*$/;

/**
 * Returns true for source versions that require serialized snapshot creation by default
 * (ES 1.x–7.x and OS 1.x). These versions do not tolerate concurrent snapshot operations
 * against the same repository reliably.
 */
export function isLegacyVersion(sourceVersion: string): boolean {
    return LEGACY_VERSION_REGEX.test(sourceVersion);
}

/**
 * Returns the default value for serializeSnapshotCreation based on the source version.
 * Legacy sources (ES 1-7, OS 1) default to serialized; modern sources default to parallel.
 */
export function defaultSerializeSnapshotCreation(sourceVersion: string): boolean {
    return isLegacyVersion(sourceVersion);
}

/**
 * Resolves serializeSnapshotCreation: the explicit override wins; otherwise fall back to
 * the version-based default.
 */
export function resolveSerializeSnapshotCreation(
    sourceVersion: string,
    override: boolean | undefined
): boolean {
    return override ?? defaultSerializeSnapshotCreation(sourceVersion);
}

/**
 * Build the Argo semaphore key used to gate concurrent snapshot creation.
 *
 * When serializeSnapshotCreation is true, all snapshots for a given source share a single
 * key (forcing them to run one-at-a-time). When false, each snapshot gets its own key
 * (allowing parallel execution).
 */
export function generateSemaphoreKey(
    serializeSnapshotCreation: boolean,
    sourceName: string,
    snapshotName: string
): string {
    if (serializeSnapshotCreation) {
        return `snapshot-legacy-${sourceName}`;
    }
    return `snapshot-modern-${sourceName}-${snapshotName}`;
}
