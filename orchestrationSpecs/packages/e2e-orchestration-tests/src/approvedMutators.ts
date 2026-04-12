import type { ApprovedMutator, ChangeClass, DependencyPattern } from './types';

/** Deep-set a value at a dot-path, supporting array indices like `foo[0].bar`. */
function deepSet(obj: Record<string, unknown>, path: string, value: unknown): Record<string, unknown> {
    const clone = JSON.parse(JSON.stringify(obj)) as Record<string, unknown>;
    // Split on `.` but also handle `[n]` segments
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
        current = (current as Record<string | number, unknown>)[parts[i]];
    }
    (current as Record<string | number, unknown>)[parts[parts.length - 1]] = value;
    return clone;
}

export { deepSet };

export const defaultMutatorRegistry: ApprovedMutator[] = [
    {
        id: 'proxy-noCapture-toggle',
        path: 'traffic.proxies.capture-proxy.proxyConfig.noCapture',
        changeClass: 'safe',
        patterns: ['focus-change'],
        apply: (config) => deepSet(config, 'traffic.proxies.capture-proxy.proxyConfig.noCapture', true),
        rationale: 'Toggling noCapture is a safe material proxy change that cascades to snapshot and replayer via checksumForSnapshot/checksumForReplayer',
    },
    {
        id: 'replayer-speedupFactor',
        path: 'traffic.replayers.replay1.replayerConfig.speedupFactor',
        changeClass: 'safe',
        patterns: ['immediate-dependent-change'],
        apply: (config) => deepSet(config, 'traffic.replayers.replay1.replayerConfig', { speedupFactor: 2.0 }),
        rationale: 'Changing speedupFactor on the replayer (an immediate dependent of proxy) only affects the replayer itself',
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
