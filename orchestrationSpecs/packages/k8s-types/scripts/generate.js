"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var crd_type_generator_1 = require("@opensearch-migrations/crd-type-generator");
(0, crd_type_generator_1.generateTypes)({
    schemaDir: './k8sSchemas',
    outputFile: './src/index.ts',
    bannerComment: '/* Generated Kubernetes type definitions — do not edit manually, run `npm run rebuild` */',
    resources: [
        { apiVersion: 'v1', kind: 'Pod' },
        { apiVersion: 'v1', kind: 'Service' },
        { apiVersion: 'v1', kind: 'ConfigMap' },
        { apiVersion: 'apps/v1', kind: 'Deployment' },
        { apiVersion: 'apps/v1', kind: 'ReplicaSet' },
        { apiVersion: 'apps/v1', kind: 'StatefulSet' },
        { apiVersion: 'batch/v1', kind: 'Job' },
        { apiVersion: 'batch/v1', kind: 'CronJob' },
    ],
    additionalTypes: [
        { name: 'Container', schemaKey: 'io.k8s.api.core.v1.Container' },
        { name: 'Volume', schemaKey: 'io.k8s.api.core.v1.Volume' },
        { name: 'VolumeMount', schemaKey: 'io.k8s.api.core.v1.VolumeMount' },
        { name: 'EnvVar', schemaKey: 'io.k8s.api.core.v1.EnvVar' },
        { name: 'PersistentVolumeClaim', schemaKey: 'io.k8s.api.core.v1.PersistentVolumeClaim' },
        { name: 'ResourceRequirements', schemaKey: 'io.k8s.api.core.v1.ResourceRequirements' },
        { name: 'PodSpec', schemaKey: 'io.k8s.api.core.v1.PodSpec' },
        { name: 'ObjectMeta', schemaKey: 'io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta' },
    ],
}).catch(console.error);
