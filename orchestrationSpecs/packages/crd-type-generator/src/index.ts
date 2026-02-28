import { compile } from 'json-schema-to-typescript';
import * as fs from 'fs';
import * as path from 'path';
import { JSONSchema4 } from 'json-schema';

export interface ResourceSpec {
    /** API version string, e.g. "v1", "apps/v1", "argoproj.io/v1alpha1" */
    apiVersion: string;
    kind: string;
}

export interface AdditionalTypeSpec {
    /** Clean alias name to export, e.g. "Container" */
    name: string;
    /** Full schema key as it appears in the OpenAPI definitions, e.g. "io.k8s.api.core.v1.Container" */
    schemaKey: string;
}

export interface GenerateOptions {
    schemaDir: string;
    outputFile: string;
    resources: ResourceSpec[];
    additionalTypes?: AdditionalTypeSpec[];
    bannerComment?: string;
}

function schemaFilename(apiVersion: string): string {
    return apiVersion === 'v1' ? 'core-v1-schema.json' : `${apiVersion.replace(/\//g, '-')}-schema.json`;
}

function updateRefs(obj: any): any {
    if (obj === null || typeof obj !== 'object') return obj;
    if (Array.isArray(obj)) return obj.map(updateRefs);
    const updated: any = {};
    for (const [key, value] of Object.entries(obj)) {
        updated[key] = key === '$ref' && typeof value === 'string'
            ? value.replace('#/components/schemas/', '#/definitions/')
            : updateRefs(value);
    }
    return updated;
}

export async function generateTypes(opts: GenerateOptions): Promise<void> {
    const { schemaDir, outputFile, resources, additionalTypes = [], bannerComment = '/* Generated CRD type definitions */' } = opts;

    fs.mkdirSync(path.dirname(outputFile), { recursive: true });

    // Load and merge all definitions from relevant schema files
    const allDefinitions: Record<string, any> = {};
    const schemaFiles = new Set(resources.map(r => schemaFilename(r.apiVersion)));
    for (const filename of schemaFiles) {
        const schemaPath = path.join(schemaDir, filename);
        if (!fs.existsSync(schemaPath)) { console.warn(`Schema not found: ${schemaPath}`); continue; }
        const definitions = JSON.parse(fs.readFileSync(schemaPath, 'utf-8')).components?.schemas || {};
        Object.assign(allDefinitions, definitions);
    }

    const masterSchema: JSONSchema4 = { type: 'object', definitions: updateRefs(allDefinitions) };
    const allTypes = await compile(masterSchema, 'CrdTypes', {
        bannerComment,
        unreachableDefinitions: true,
        strictIndexSignatures: false,
    });

    // Build a map from schema key → generated interface name
    const typeNameMap = new Map<string, string>();
    const interfaceRegex = /via the `definition` "([^"]+)"\.[\s\S]*?\nexport interface (\w+)/g;
    let match;
    while ((match = interfaceRegex.exec(allTypes)) !== null) {
        typeNameMap.set(match[1], match[2]);
    }

    const makeAliases = (specs: Array<{ name: string; schemaKey: string }>) =>
        specs.map(({ name, schemaKey }) => {
            const t = typeNameMap.get(schemaKey);
            if (!t) { console.warn(`Could not find generated type for ${name} (${schemaKey})`); return `// export type ${name} = unknown; // ${schemaKey} not found`; }
            return `export type ${name} = ${t};`;
        }).join('\n');

    const resourceAliases = makeAliases(
        resources.flatMap(r => {
            const schemaKey = Object.keys(allDefinitions).find(k => k.endsWith(`.${r.kind}`));
            return schemaKey ? [{ name: r.kind, schemaKey }] : (console.warn(`${r.kind} not found in schemas`), []);
        })
    );

    fs.writeFileSync(outputFile,
        `${allTypes}\n// Clean type aliases\n${resourceAliases}\n${additionalTypes.length ? '\n' + makeAliases(additionalTypes) + '\n' : ''}`
    );
    console.log(`Generated ${outputFile}`);
}
