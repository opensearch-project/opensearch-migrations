"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var crd_type_generator_1 = require("@opensearch-migrations/crd-type-generator");
(0, crd_type_generator_1.generateTypes)({
    schemaDir: './k8sSchemas',
    outputFile: './src/index.ts',
    bannerComment: '/* Generated Strimzi type definitions — do not edit manually, run `npm run rebuild` */',
    resources: [
        { apiVersion: 'kafka.strimzi.io/v1', kind: 'Kafka' },
        { apiVersion: 'kafka.strimzi.io/v1', kind: 'KafkaNodePool' },
        { apiVersion: 'kafka.strimzi.io/v1', kind: 'KafkaTopic' },
    ],
}).catch(console.error);
