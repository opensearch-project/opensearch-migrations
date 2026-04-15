import type { ApprovedMutator, ChangeClass, DependencyPattern } from './types';

/** Deep-set a value at a dot-path, supporting array indices like `foo[0].bar`. Creates missing intermediate objects. */
function deepSet(obj: Record<string, unknown>, path: string, value: unknown): Record<string, unknown> {
    const clone = JSON.parse(JSON.stringify(obj)) as Record<string, unknown>;
    const parts: (string | number)[] = [];
    for (const segment of path.split('.')) {
        const match = /^([^[]+)\[(\d+)]$/.exec(segment);
        if (match) {
            parts.push(match[1], Number(match[2]));
        } else {
            parts.push(segment);
        }
    }
    let current: unknown = clone;
    for (let i = 0; i < parts.length - 1; i++) {
        const key = parts[i];
        const container = current as Record<string | number, unknown>;
        if (container[key] === undefined || container[key] === null) {
            // Create intermediate: array if next key is a number, object otherwise
            container[key] = typeof parts[i + 1] === 'number' ? [] : {};
        }
        current = container[key];
    }
    (current as Record<string | number, unknown>)[parts[parts.length - 1]] = value;
    return clone;
}

export { deepSet };

export const defaultMutatorRegistry: ApprovedMutator[] = [
    {
        id: 'proxy-numThreads',
        path: 'traffic.proxies.capture-proxy.proxyConfig.numThreads',
        changeClass: 'safe',
        patterns: ['focus-change'],
        apply: (config) => deepSet(config, 'traffic.proxies.capture-proxy.proxyConfig.numThreads', 2),
        rationale: 'Changing numThreads is a safe proxy change — not gated by admission policy — that cascades to snapshot and replayer via checksumForSnapshot/checksumForReplayer',
    },
    {
        id: 'proxy-noCapture-toggle',
        path: 'traffic.proxies.capture-proxy.proxyConfig.noCapture',
        changeClass: 'gated',
        patterns: ['focus-gated-change'],
        apply: (config) => deepSet(config, 'traffic.proxies.capture-proxy.proxyConfig.noCapture', true),
        rationale: 'Toggling noCapture is gated by ValidatingAdmissionPolicy — requires approval annotation to proceed',
    },
    {
        id: 'replayer-speedupFactor',
        path: 'traffic.replayers.replay1.replayerConfig.speedupFactor',
        changeClass: 'safe',
        patterns: ['immediate-dependent-change'],
        apply: (config) => deepSet(config, 'traffic.replayers.replay1.replayerConfig.speedupFactor', 2.0),
        rationale: 'Changing speedupFactor on the replayer (an immediate dependent of proxy) only affects the replayer itself',
    },
    {
        id: 'replayer-removeAuthHeader',
        path: 'traffic.replayers.replay1.replayerConfig.removeAuthHeader',
        changeClass: 'gated',
        patterns: ['immediate-dependent-gated-change'],
        apply: (config) => deepSet(config, 'traffic.replayers.replay1.replayerConfig.removeAuthHeader', true),
        rationale: 'Toggling removeAuthHeader on the replayer is gated — requires approval before the replayer restarts with auth stripping enabled',
    },
    {
        id: 'replayer-invalidTimeout',
        path: 'traffic.replayers.replay1.replayerConfig.targetServerResponseTimeoutSeconds',
        changeClass: 'impossible',
        patterns: ['immediate-dependent-impossible-change'],
        apply: (config) => deepSet(config, 'traffic.replayers.replay1.replayerConfig.targetServerResponseTimeoutSeconds', -1),
        rationale: 'Setting an invalid timeout is impossible to reconcile in place — the replayer cannot start with this value, requiring delete and recreate',
    },
    {
        id: 'rfs-maxConnections',
        path: 'snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0].documentBackfillConfig.maxConnections',
        changeClass: 'safe',
        patterns: ['transitive-dependent-change'],
        apply: (config) => deepSet(config, 'snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0].documentBackfillConfig.maxConnections', 8),
        rationale: 'Changing maxConnections on the RFS backfill (a transitive dependent of proxy via snapshot) only affects the snapshot migration itself',
    },
];

export function findMutators(
    registry: ApprovedMutator[],
    changeClass: ChangeClass,
    pattern: DependencyPattern,
): ApprovedMutator[] {
    return registry.filter(
        m => m.changeClass === changeClass && m.patterns.includes(pattern)
    );
}
