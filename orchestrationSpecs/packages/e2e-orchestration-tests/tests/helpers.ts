import fs from 'node:fs';
import path from 'node:path';
import yaml from 'yaml';

const fixturePath = path.resolve(
    __dirname,
    '..', '..', 'config-processor', 'scripts', 'samples', 'fullMigrationWithTraffic.wf.yaml'
);

export function loadFullMigrationConfig(): Record<string, unknown> {
    return yaml.parse(fs.readFileSync(fixturePath, 'utf-8')) as Record<string, unknown>;
}
