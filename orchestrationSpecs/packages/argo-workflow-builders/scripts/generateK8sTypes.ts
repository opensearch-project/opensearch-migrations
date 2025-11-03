import { compile } from 'json-schema-to-typescript';
import * as fs from 'fs';
import * as path from 'path';
import {JSONSchema4} from "json-schema";

// Helper function to recursively update $ref pointers
function updateRefs(obj: any): any {
    if (obj === null || typeof obj !== 'object') {
        return obj;
    }

    if (Array.isArray(obj)) {
        return obj.map(updateRefs);
    }

    const updated: any = {};
    for (const [key, value] of Object.entries(obj)) {
        if (key === '$ref' && typeof value === 'string') {
            // Convert #/components/schemas/... to #/definitions/...
            updated[key] = value.replace('#/components/schemas/', '#/definitions/');
        } else {
            updated[key] = updateRefs(value);
        }
    }
    return updated;
}

async function generateTypes(
    schemaDir: string,
    outputFile: string,
    resources: Array<{ apiVersion: string; kind: string }>,
    additionalTypes: Array<{ name: string; schemaKey: string }> = []
): Promise<void> {
    // Create output directory
    const outputDir = path.dirname(outputFile);
    if (!fs.existsSync(outputDir)) {
        fs.mkdirSync(outputDir, { recursive: true });
    }

    // Collect all definitions
    const allDefinitions: Record<string, any> = {};
    const schemaFiles = new Set<string>();
    const resourceSchemaKeys: Array<{ kind: string; schemaKey: string }> = [];

    for (const resource of resources) {
        const filename = resource.apiVersion === 'v1'
            ? 'core-v1-schema.json'
            : `${resource.apiVersion.replace(/\//g, '-')}-schema.json`;
        schemaFiles.add(filename);
    }

    // Load and merge all definitions
    for (const filename of schemaFiles) {
        const schemaPath = path.join(schemaDir, filename);
        if (!fs.existsSync(schemaPath)) {
            console.warn(`Schema not found: ${schemaPath}`);
            continue;
        }

        const schemaContent = JSON.parse(fs.readFileSync(schemaPath, 'utf-8'));
        const definitions = schemaContent.components?.schemas || {};
        Object.assign(allDefinitions, definitions);
    }

    // Find schema keys for each resource
    for (const resource of resources) {
        const schemaKey = Object.keys(allDefinitions).find(key => key.endsWith(`.${resource.kind}`));
        if (schemaKey) {
            resourceSchemaKeys.push({ kind: resource.kind, schemaKey });
        } else {
            console.warn(`${resource.kind} not found in schemas`);
        }
    }

    // Update all $ref pointers from #/components/schemas/ to #/definitions/
    const updatedDefinitions = updateRefs(allDefinitions);

    // Create a master schema with all definitions
    const masterSchema: JSONSchema4 = {
        type: 'object',
        definitions: updatedDefinitions
    };

    // Generate all types at once
    const allTypes = await compile(masterSchema, 'K8sTypes', {
        bannerComment: '/* Generated Kubernetes type definitions */',
        unreachableDefinitions: true,
        strictIndexSignatures: false,
    });

    // Parse the generated types to find actual interface/type names
    const typeNameMap = new Map<string, string>();
    const interfaceRegex = /via the `definition` "([^"]+)"\.[\s\S]*?\nexport interface (\w+)/g;
    let match;
    while ((match = interfaceRegex.exec(allTypes)) !== null) {
        const [, schemaKey, typeName] = match;
        typeNameMap.set(schemaKey, typeName);
    }

    console.log('Found type mappings for resources:');
    resourceSchemaKeys.forEach(({ kind, schemaKey }) => {
        console.log(`  ${kind} -> ${schemaKey} -> ${typeNameMap.get(schemaKey) || 'NOT FOUND'}`);
    });

    console.log('\nFound type mappings for additional types:');
    additionalTypes.forEach(({ name, schemaKey }) => {
        console.log(`  ${name} -> ${schemaKey} -> ${typeNameMap.get(schemaKey) || 'NOT FOUND'}`);
    });

    // Generate type aliases for clean names - resources
    const resourceAliases = resourceSchemaKeys.map(({ kind, schemaKey }) => {
        const generatedTypeName = typeNameMap.get(schemaKey);

        if (!generatedTypeName) {
            console.warn(`Could not find generated type for ${kind} (${schemaKey})`);
            return `// export type ${kind} = unknown; // Could not find type for ${schemaKey}`;
        }

        return `export type ${kind} = ${generatedTypeName};`;
    }).join('\n');

    // Generate type aliases for additional types
    const additionalAliases = additionalTypes.map(({ name, schemaKey }) => {
        const generatedTypeName = typeNameMap.get(schemaKey);

        if (!generatedTypeName) {
            console.warn(`Could not find generated type for ${name} (${schemaKey})`);
            return `// export type ${name} = unknown; // Could not find type for ${schemaKey}`;
        }

        return `export type ${name} = ${generatedTypeName};`;
    }).join('\n');

    // Combine generated types with aliases
    const finalContent = `${allTypes}\n\n// Clean type aliases for main resources\n${resourceAliases}\n\n// Clean type aliases for commonly used types\n${additionalAliases}\n`;

    fs.writeFileSync(outputFile, finalContent);
    console.log(`\nGenerated ${outputFile} with ${resourceSchemaKeys.length} resource type aliases and ${additionalTypes.length} additional type aliases`);
}

async function main() {
    await generateTypes(
        './k8sSchemas',
        './src/kubernetesResourceTypes/kubernetesTypes.ts',
        [
            { apiVersion: 'v1', kind: 'Pod' },
            { apiVersion: 'v1', kind: 'Service' },
            { apiVersion: 'v1', kind: 'ConfigMap' },
            { apiVersion: 'apps/v1', kind: 'Deployment' },
            { apiVersion: 'apps/v1', kind: 'ReplicaSet' },
            { apiVersion: 'apps/v1', kind: 'StatefulSet' },
            { apiVersion: 'batch/v1', kind: 'Job' },
            { apiVersion: 'batch/v1', kind: 'CronJob' },
        ],
        [
            // Common sub-types
            { name: 'Container', schemaKey: 'io.k8s.api.core.v1.Container' },
            { name: 'Volume', schemaKey: 'io.k8s.api.core.v1.Volume' },
            { name: 'VolumeMount', schemaKey: 'io.k8s.api.core.v1.VolumeMount' },
            { name: 'EnvVar', schemaKey: 'io.k8s.api.core.v1.EnvVar' },
            { name: 'PersistentVolumeClaim', schemaKey: 'io.k8s.api.core.v1.PersistentVolumeClaim' },
            { name: 'ResourceRequirements', schemaKey: 'io.k8s.api.core.v1.ResourceRequirements' },
            { name: 'PodSpec', schemaKey: 'io.k8s.api.core.v1.PodSpec' },
            { name: 'ObjectMeta', schemaKey: 'io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta' },
        ]
    );
}

if (require.main === module) {
    main().catch(console.error);
}