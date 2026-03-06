import { generateTypes } from '@opensearch-migrations/crd-type-generator';

generateTypes({
    schemaDir: './k8sSchemas',
    outputFile: './src/index.ts',
    bannerComment: '/* Generated Argo Workflows type definitions — do not edit manually, run `npm run rebuild` */',
    resources: [
        { apiVersion: 'argoproj.io/v1alpha1', kind: 'Workflow' },
        { apiVersion: 'argoproj.io/v1alpha1', kind: 'WorkflowTemplate' },
        { apiVersion: 'argoproj.io/v1alpha1', kind: 'CronWorkflow' },
    ],
}).catch(console.error);
