import fs from 'node:fs';
import path from 'node:path';
import yaml from 'yaml';

const samplesDir = path.resolve(__dirname, '..', '..', 'config-processor', 'scripts', 'samples');

export function loadFullMigrationConfig(): Record<string, unknown> {
    return yaml.parse(fs.readFileSync(path.join(samplesDir, 'fullMigrationWithTraffic.wf.yaml'), 'utf-8')) as Record<string, unknown>;
}

export function loadProxyOnlyConfig(): Record<string, unknown> {
    return yaml.parse(fs.readFileSync(path.join(samplesDir, 'proxyOnly.wf.yaml'), 'utf-8')) as Record<string, unknown>;
}
