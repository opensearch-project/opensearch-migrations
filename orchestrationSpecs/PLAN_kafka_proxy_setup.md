# Plan: Kafka Cluster + Proxy Setup

## Goals
- Fill out `KAFKA_CLUSTER_CREATION_CONFIG` with user-configurable Strimzi fields
- Remove Zookeeper support (KRaft only)
- Make Kafka cluster + topic creation idempotent (`action: apply`)
- Implement real proxy Deployment + Service in `setupCapture.ts`
- Wire everything into `fullMigration.ts` replacing the placeholder
- Delete `captureReplay.ts` (fully commented out, unused)

## Service design
- `LoadBalancer` Service named `<proxyName>` with NLB IP mode annotations
  - On EKS Auto Mode: annotations activate direct pod routing
  - On minikube/standard K8s: annotations are ignored, standard LoadBalancer behavior
- Deployment named `<proxyName>`, both use `action: apply` (idempotent)

## Changes

### `userSchemas.ts`
- `KAFKA_CLUSTER_CREATION_CONFIG`: add `replicas`, `version`, `storage` (ephemeral or persistent-claim), `partitions`, `topicReplicas`, `useKraft` removed (always KRaft)
- `PROXY_OPTIONS`: already has `listenPort`, `podReplicas`, `otelCollectorEndpoint` — no new fields needed

### `argoSchemas.ts`
- `NAMED_KAFKA_CLUSTER_CONFIG`: already carries `config` (typed from `KAFKA_CLUSTER_CREATION_CONFIG`) — no structural change needed, just picks up the new fields automatically

### `setupKafka.ts`
- Remove `deployKafkaClusterZookeeper` and `deployKafkaClusterZookeeper` template
- Remove `useKraft` input from `deployKafkaCluster` (always KRaft)
- Change KafkaNodePool `action: apply` (already done)
- Change KafkaCluster `action: apply` (already done for KRaft)
- Thread `replicas`, `version`, `storage`, `partitions`, `topicReplicas` from inputs

### `setupCapture.ts`
- Replace stub with:
  - `deployProxyService`: `LoadBalancer` Service with NLB annotations, `action: apply`
  - `deployProxyDeployment`: `Deployment` with env vars from `DENORMALIZED_PROXY_CONFIG`, `action: apply`
  - `setupProxy`: orchestrates topic creation → service → deployment

### `fullMigration.ts`
- `setupSingleProxy`: replace `placeholderProxySetup` with real `SetupCapture.setupProxy` call
- `setupSingleKafkaCluster`: thread config fields (partitions, topicReplicas) into topic creation

### `captureReplay.ts`
- Delete (fully commented out, no consumers)

## TODO / Future
- MSK auth (kafka auth)
- SSL config for proxy
- Header overrides
- MetalLB / Cilium L2 for on-prem LoadBalancer
