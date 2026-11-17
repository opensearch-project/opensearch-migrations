import * as fs from 'fs';
import * as path from 'path';
import { parse } from 'yaml';
import { MigrationConfigTransformer } from '../src/migrationConfigTransformer';
import { formatInputValidationError, InputValidationError } from '../src/streamSchemaTransformer';

// Sample migration configs must pass the same validation that `workflow configure` runs
// (find-secrets -> MigrationConfigTransformer.validateInput), so a stale sample fails here rather than
// for a user who copies it. This package's samples are always checked; the build passes other sample
// directories in SAMPLE_CONFIG_DIRS (path-delimiter separated).
const SAMPLE_DIRS = [
    path.resolve(__dirname, '../scripts'),
    ...(process.env.SAMPLE_CONFIG_DIRS ?? '').split(path.delimiter).filter(dir => dir !== ''),
];

// The quick-start configs carry placeholders that their guide fills in with sed before loading them.
const PLACEHOLDERS: Record<string, string> = {
    '<TARGET_DOMAIN_ENDPOINT>': 'https://vpc-qs-target-abc123.us-east-2.es.amazonaws.com',
    '<COLLECTION_ENDPOINT>': 'https://abc123xyz.us-east-2.aoss.amazonaws.com',
    '<REGION>': 'us-east-2',
    '<BUCKET>': 'migrations-default-123456789012-qs-us-east-2',
};

function yamlFiles(dir: string): string[] {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            return entry.name === 'node_modules' ? [] : yamlFiles(full);
        }
        return /\.ya?ml$/.test(entry.name) ? [full] : [];
    });
}

// Helm values and other YAML live beside the samples; a migration config has these top-level keys.
function isMigrationConfig(data: unknown): boolean {
    return typeof data === 'object' && data !== null &&
        ['sourceClusters', 'targetClusters'].some(key => key in data);
}

function fillPlaceholders(text: string): string {
    return Object.entries(PLACEHOLDERS).reduce((t, [placeholder, value]) => t.split(placeholder).join(value), text);
}

const samples = SAMPLE_DIRS.flatMap(dir => yamlFiles(dir).map(file => ({
    dir,
    file: path.join(path.basename(dir), path.relative(dir, file)),
    data: parse(fillPlaceholders(fs.readFileSync(file, 'utf8'))),
}))).filter(sample => isMigrationConfig(sample.data));

describe('sample migration configs', () => {
    it.each(SAMPLE_DIRS)('finds migration configs in %s', dir => {
        expect(samples.some(sample => sample.dir === dir)).toBe(true);
    });

    it.each(samples.map(s => [s.file, s.data]))('%s passes workflow configure validation', (_file, data) => {
        try {
            new MigrationConfigTransformer().validateInput(data);
        } catch (error) {
            throw error instanceof InputValidationError ? new Error(formatInputValidationError(error)) : error;
        }
    });
});
