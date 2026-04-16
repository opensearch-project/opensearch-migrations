import { describe, it, expect } from '@jest/globals';
import { z } from 'zod';
import { zodSchemaToJsonSchema } from '../src/getSchemaFromZod';
import '../src/userSchemas'; // load prototype extensions

describe('JSON Schema x- extension injection', () => {
    const SCHEMA = z.object({
        tagged: z.string()
            .describe('A tagged field')
            .checksumFor('snapshot', 'replayer')
            .changeRestriction('gated'),
        untagged: z.number()
            .describe('An untagged field'),
        restrictionOnly: z.boolean()
            .describe('Only has restriction')
            .changeRestriction('impossible'),
        withDefault: z.string().default('x').optional()
            .describe('Has default and optional wrappers')
            .checksumFor('replayer'),
    });

    const jsonSchema = zodSchemaToJsonSchema(SCHEMA, 'Test');

    it('injects x-checksum-for on tagged fields', () => {
        expect(jsonSchema.properties.tagged['x-checksum-for']).toEqual(['snapshot', 'replayer']);
    });

    it('injects x-change-restriction on restricted fields', () => {
        expect(jsonSchema.properties.tagged['x-change-restriction']).toEqual('gated');
        expect(jsonSchema.properties.restrictionOnly['x-change-restriction']).toEqual('impossible');
    });

    it('does NOT inject x- extensions on untagged fields', () => {
        expect(jsonSchema.properties.untagged['x-checksum-for']).toBeUndefined();
        expect(jsonSchema.properties.untagged['x-change-restriction']).toBeUndefined();
    });

    it('works through .default().optional() wrappers', () => {
        expect(jsonSchema.properties.withDefault['x-checksum-for']).toEqual(['replayer']);
    });

    it('preserves the description alongside extensions', () => {
        expect(jsonSchema.properties.tagged.description).toEqual('A tagged field');
    });
});
