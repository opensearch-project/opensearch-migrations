import { describe, it, expect } from '@jest/globals';
import {formatInputValidationError, InputValidationError, MigrationConfigTransformer} from "../src";
import {ZodError} from "zod";

async function getThrown<T = Error>(fn: () => Promise<void>): Promise<T> {
    try {
        await fn();
        throw new Error('Expected function to throw');
    } catch (e) {
        return e as T;
    }
}

describe('errorsArePrintedSuccinctly', () => {
    it('when there is a syntax error, the path is in dot notation', async () => {
        const config = {
            sourceClusters: {
                source1: {
                    authConfig: {
                        digest: {},
                        basic: {}
                    }
                }
            },
            targetClusters: {},
        };

        const processor = new MigrationConfigTransformer();
        const error: InputValidationError = await getThrown(async () => {
            await processor.processFromObject(config);
        });
        expect(formatInputValidationError(error)).toBe(
            `Invalid input... at:\n` +
            `  sourceClusters.source1.authConfig\n` +
            `Invalid input: expected string, received undefined... at:\n` +
            `  sourceClusters.source1.version\n` +
            `Invalid input: expected array, received undefined... at:\n` +
            `  migrationConfigs`);
    });
});
