import { describe, it, expect } from '@jest/globals';
import { z } from 'zod';
import { MigrationConfigTransformer } from '../src';
import '../../../packages/schemas/src/userSchemas'; // load prototype extensions

const cs = MigrationConfigTransformer.configChecksum;
const csDep = MigrationConfigTransformer.checksumForDependency;

// Dummy schema simulating a component with mixed annotations
const DUMMY_SCHEMA = z.object({
    materialForA: z.string().checksumFor('snapshot'),
    materialForBoth: z.number().checksumFor('snapshot', 'replayer'),
    operational: z.number(),
    gatedButNotMaterial: z.string().changeRestriction('gated'),
});

const BASE_DATA = {
    materialForA: 'hello',
    materialForBoth: 42,
    operational: 3,
    gatedButNotMaterial: 'secret',
};

describe('checksumForDependency', () => {
    it('includes only fields tagged for the requested dependency', () => {
        const forSnapshot = csDep(DUMMY_SCHEMA, BASE_DATA, 'snapshot');
        const forReplayer = csDep(DUMMY_SCHEMA, BASE_DATA, 'replayer');
        // snapshot includes materialForA + materialForBoth; replayer only materialForBoth
        expect(forSnapshot).not.toEqual(forReplayer);
    });

    it('changing a tagged field changes the dependency checksum', () => {
        const before = csDep(DUMMY_SCHEMA, BASE_DATA, 'snapshot');
        const after = csDep(DUMMY_SCHEMA, { ...BASE_DATA, materialForA: 'changed' }, 'snapshot');
        expect(before).not.toEqual(after);
    });

    it('changing an untagged field does NOT change any dependency checksum', () => {
        const snapshotBefore = csDep(DUMMY_SCHEMA, BASE_DATA, 'snapshot');
        const replayerBefore = csDep(DUMMY_SCHEMA, BASE_DATA, 'replayer');
        const modified = { ...BASE_DATA, operational: 999, gatedButNotMaterial: 'different' };
        expect(csDep(DUMMY_SCHEMA, modified, 'snapshot')).toEqual(snapshotBefore);
        expect(csDep(DUMMY_SCHEMA, modified, 'replayer')).toEqual(replayerBefore);
    });

    it('upstream checksum folding: same local fields but different upstream → different result', () => {
        const withUpstreamA = csDep(DUMMY_SCHEMA, BASE_DATA, 'snapshot', 'upstream-aaa');
        const withUpstreamB = csDep(DUMMY_SCHEMA, BASE_DATA, 'snapshot', 'upstream-bbb');
        expect(withUpstreamA).not.toEqual(withUpstreamB);
    });

    it('dependency with no tagged fields returns a checksum of the empty pick', () => {
        // 'snapshotMigration' is not tagged on any field in DUMMY_SCHEMA
        const result = csDep(DUMMY_SCHEMA, BASE_DATA, 'snapshotMigration');
        // Should still be a valid hex string (hash of empty object)
        expect(result).toMatch(/^[0-9a-f]{16}$/);
        // And it should be stable
        expect(csDep(DUMMY_SCHEMA, { ...BASE_DATA, materialForA: 'x' }, 'snapshotMigration')).toEqual(result);
    });

    it('self checksum (configChecksum) changes when operational fields change', () => {
        const before = cs(BASE_DATA);
        const after = cs({ ...BASE_DATA, operational: 999 });
        expect(before).not.toEqual(after);
    });
});
