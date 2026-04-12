import { defaultMutatorRegistry, findMutators } from '../src/approvedMutators';
import { loadFullMigrationConfig } from './helpers';
import { MigrationConfigTransformer } from '@opensearch-migrations/config-processor';

describe('approvedMutators', () => {
    const config = loadFullMigrationConfig();

    it('all mutators produce valid configs', () => {
        const transformer = new MigrationConfigTransformer();
        for (const mutator of defaultMutatorRegistry) {
            const mutated = mutator.apply(config);
            expect(() => transformer.validateInput(mutated)).not.toThrow();
        }
    });

    it('findMutators returns focus-change safe mutators', () => {
        const result = findMutators(defaultMutatorRegistry, 'safe', 'focus-change');
        expect(result.length).toBeGreaterThanOrEqual(1);
        expect(result[0].id).toBe('proxy-noCapture-toggle');
    });

    it('findMutators returns immediate-dependent-change safe mutators', () => {
        const result = findMutators(defaultMutatorRegistry, 'safe', 'immediate-dependent-change');
        expect(result.length).toBeGreaterThanOrEqual(1);
        expect(result[0].id).toBe('replayer-speedupFactor');
    });

    it('findMutators returns transitive-dependent-change safe mutators', () => {
        const result = findMutators(defaultMutatorRegistry, 'safe', 'transitive-dependent-change');
        expect(result.length).toBeGreaterThanOrEqual(1);
        expect(result[0].id).toBe('rfs-maxConnections');
    });

    it('findMutators returns empty for non-matching criteria', () => {
        const result = findMutators(defaultMutatorRegistry, 'impossible', 'focus-change');
        expect(result).toHaveLength(0);
    });
});
