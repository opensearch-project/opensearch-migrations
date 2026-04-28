# E2E Test Results

## Test 1: Non-Isolated (mirror cluster)

**Cluster:** migration-eks-cluster-mirror-us-east-2  
**Network:** Private subnets with NAT gateway (internet access)  
**S3 endpoint:** Default (global, no override needed)

### Setup
- testClusters deployed with `valuesEks.yaml` + `useDedicatedKarpenterPool=false` + `source.nodeAffinity=null` + `source.tolerations=null` + `target.nodeAffinity=null` + `target.tolerations=null`
- ES image: `<ACCOUNT_ID>.dkr.ecr.us-east-2.amazonaws.com/migration-ecr-isolated-us-east-2:migrations_elasticsearch_searchguard_latest`
- OS image: `opensearchproject/opensearch:2.11.1` (public, pulled via NAT)
- Note: OS 2.11.1 uses default `admin:admin` password (OPENSEARCH_INITIAL_ADMIN_PASSWORD not supported until 2.12)

### Data Generation
```
Generating 500 documents in index 'test-migration-data' on source_cluster
Documents inserted: 500, Errors: 0, Time: 1.0s, Rate: 491.9 docs/sec
```

### Workflow Result
```
Phase: Succeeded
├── ✓ createSnapshot (Succeeded)
├── ✓ checkSnapshotCompletion (Succeeded)
├── ✓ evaluateMetadata (Succeeded)
├── ✓ migrateMetadata (Succeeded)
├── ✓ startHistoricalBackfill (Succeeded)
├── ✓ checkBackfillStatus (Succeeded): complete: 0.00%, ETA: unknown
└── ✓ stopHistoricalBackfill (Succeeded)
```

### Validation
- Source: 500 docs in `test-migration-data`
- Target: **500 docs** in `test-migration-data` (verified with `_count` after `_refresh`)
- Result: ✅ PASS

### Notes
- The "complete: 0.00%" in backfill status is a display artifact for small/fast migrations — actual doc count confirmed via `_count`
- ES pod uses ephemeral storage; data survives as long as pod is running

---

## Test 2: Isolated (isolated cluster)

**Cluster:** migration-eks-cluster-isolated-us-east-2  
**Network:** Private subnets with NO NAT/IGW (fully isolated)  
**S3 endpoint:** `https://s3.us-east-2.amazonaws.com` (regional, required for Gateway VPC endpoint routing)

### Key Difference from Non-Isolated
The global S3 endpoint (`s3.amazonaws.com`) resolves to IPs outside the regional VPC Gateway endpoint prefix list. ES 7.10.2's `s3.client.default.endpoint` must be set to the regional endpoint so DNS resolves to IPs covered by the prefix list.

**Fix applied:**
1. `valuesEks.yaml` now sets `s3.client.default.endpoint: ${S3_CLIENT_ENDPOINT:}` in `elasticsearch.yml`
2. Helm upgrade passes `--set source.extraEnvs[0].value=s3.us-east-2.amazonaws.com`
3. Workflow config sets `snapshotRepos.endpoint: https://s3.us-east-2.amazonaws.com`

### Setup
- testClusters upgraded with `valuesEks.yaml` + `source.extraEnvs[0].value=s3.us-east-2.amazonaws.com`
- ES image: `<ACCOUNT_ID>.dkr.ecr.us-east-2.amazonaws.com/migration-ecr-isolated-us-east-2:migrations_elasticsearch_searchguard_latest`
- OS image: `<ACCOUNT_ID>.dkr.ecr.us-east-2.amazonaws.com/mirrored/mirror.gcr.io/opensearchproject/opensearch:2.19.1` (mirrored ECR, no internet needed)

### Data Generation
```
Generating 500 documents in index 'test-migration-data' on source_cluster
Documents inserted: 500, Errors: 0, Time: 1.1s, Rate: 454.9 docs/sec
```

### Workflow Result
```
Phase: Succeeded
├── ✓ createSnapshot (Succeeded)
├── ✓ checkSnapshotCompletion (Succeeded)
├── ✓ evaluateMetadata (Succeeded)
├── ✓ migrateMetadata (Succeeded)
├── ✓ startHistoricalBackfill (Succeeded)
├── ✓ checkBackfillStatus (Succeeded): complete: 0.00%, ETA: unknown
└── ✓ stopHistoricalBackfill (Succeeded)
```

### Validation
- Source: 500 docs in `test-migration-data`
- Target: **500 docs** in `test-migration-data` (verified with `_count` after `_refresh`)
- Result: ✅ PASS

---

## Summary

| | Non-Isolated (mirror) | Isolated |
|---|---|---|
| Cluster | migration-eks-cluster-mirror-us-east-2 | migration-eks-cluster-isolated-us-east-2 |
| Network | NAT gateway | No internet |
| S3 endpoint override | Not needed | Required (`s3.us-east-2.amazonaws.com`) |
| Source docs | 500 | 500 |
| Target docs | 500 ✅ | 500 ✅ |
| Duration | ~4 min | ~5 min |
