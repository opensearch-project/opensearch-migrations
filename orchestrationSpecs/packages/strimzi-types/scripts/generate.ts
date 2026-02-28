import { generateTypes } from '@opensearch-migrations/crd-type-generator';

generateTypes({
    schemaDir: './k8sSchemas',
    outputFile: './src/index.ts',
    bannerComment: '/* Generated Strimzi type definitions — do not edit manually, run `npm run rebuild` */',
    resources: [
        { apiVersion: 'kafka.strimzi.io/v1beta2', kind: 'Kafka' },
        { apiVersion: 'kafka.strimzi.io/v1beta2', kind: 'KafkaNodePool' },
        { apiVersion: 'kafka.strimzi.io/v1beta2', kind: 'KafkaTopic' },
    ],
}).catch(console.error);
