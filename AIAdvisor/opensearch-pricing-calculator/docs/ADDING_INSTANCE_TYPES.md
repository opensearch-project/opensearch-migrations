# Adding New Instance Types to OpenSearch Calculator

This guide documents the process for adding new AWS OpenSearch instance types to the calculator service.

## Overview

When AWS releases new instance types (e.g., r8g family), they need to be added to the calculator's instance limits configuration. The pricing data is automatically fetched from AWS, but storage limits must be manually configured.

## Files to Modify

### 1. Instance Limits Configuration
**File:** `impl/instances/limits.go`

Add entries to the `InstanceLimitsMap` for each new instance type.

**Example - Adding r8g instances:**
```go
var InstanceLimitsMap = map[string]InstanceLimits{
    // ... existing instances ...

    // R8G Memory Optimized instances
    "r8g.medium.search":     {Minimum: 10, MaximumGp3: 768, MaxNodesCount: 400},
    "r8g.large.search":      {Minimum: 10, MaximumGp3: 1532, MaxNodesCount: 400},
    "r8g.xlarge.search":     {Minimum: 10, MaximumGp3: 3072, MaxNodesCount: 400},
    "r8g.2xlarge.search":    {Minimum: 10, MaximumGp3: 6144, MaxNodesCount: 400},
    "r8g.4xlarge.search":    {Minimum: 10, MaximumGp3: 12288, MaxNodesCount: 400},
    "r8g.8xlarge.search":    {Minimum: 10, MaximumGp3: 16384, MaxNodesCount: 400},
    "r8g.12xlarge.search":   {Minimum: 10, MaximumGp3: 24576, MaxNodesCount: 400},
    "r8g.16xlarge.search":   {Minimum: 10, MaximumGp3: 36864, MaxNodesCount: 400},
}
```

**Fields:**
- `Minimum` - Minimum EBS volume size in GiB (typically 10 or 20)
- `MaximumGp3` - Maximum GP3 EBS volume size in GiB
- `MaximumGp2` - Maximum GP2 EBS volume size in GiB (optional, for older instances)
- `MaxNodesCount` - Maximum number of nodes allowed for this instance type

**Where to find limits:**
- AWS OpenSearch Service documentation
- AWS Service Quotas console
- OpenSearch Service documentation

## Important Fix (October 2025)

### Issue: Storage Limits Not Populated for New Instance Types

**Problem:**
- AWS pricing API doesn't always include a "Storage" field for newer instance types
- The original code only checked `InstanceLimitsMap` when AWS provided a Storage field
- This caused new instance types (like r8g) to have empty storage limits even after being added to `InstanceLimitsMap`

**Solution:**
The code in `impl/cache/provisioned.go` (function `createNewOnDemandHotInstanceUnit`) was updated to fallback to `InstanceLimitsMap` when AWS doesn't provide storage information:

```go
// Around line 440-449
} else {
    // Fallback: If AWS doesn't provide Storage field, check InstanceLimitsMap
    // This handles newer instance types (like r8g) where AWS pricing API may not include Storage
    ebsMap, found := instances.InstanceLimitsMap[iu.InstanceType]
    if found {
        iu.Storage.MinEBS = ebsMap.Minimum
        iu.Storage.MaxGP2 = ebsMap.MaximumGp2
        iu.Storage.MaxGP3 = ebsMap.MaximumGp3
    }
}
```

This ensures that instance limits are populated from `InstanceLimitsMap` regardless of whether AWS includes Storage information in their pricing API.

## Step-by-Step Process

### Step 1: Add Instance Limits
Edit `impl/instances/limits.go` and add entries for all new instance sizes.

### Step 2: Rebuild the Service
```bash
cd os-calculator-services
go build -o os-calculator-service .
```

### Step 3: Start the Service
```bash
./os-calculator-service
```

The service will start on ports 8080 (HTTP) and 8081 (MCP).

### Step 4: Regenerate Price Cache
Invalidate the cache to fetch AWS pricing and apply your instance limits:

```bash
curl -X POST "http://localhost:8080/provisioned/cache/invalidate?update=true"
```

**Note:** This takes 2-4 minutes as it downloads pricing from AWS for all regions.

### Step 5: Verify the Changes
Check that storage limits are properly populated:

```bash
# Check a specific instance
jq '.["US East (N. Virginia)"].hotInstances["r8g.large.search"].instanceStorage' priceCache.json

# Check all instances of a family
jq '.["US East (N. Virginia)"].hotInstances | to_entries | map(select(.key | contains("r8g"))) | .[].value | {instanceType, instanceStorage}' priceCache.json
```

**Expected output:**
```json
{
  "minEBS": 10,
  "maxGP3": 1532
}
```

If `instanceStorage` is empty (`{}`), the fix in Step 2 was not applied or the cache wasn't regenerated.

## Data Flow

1. **AWS Pricing API** → Downloaded by `impl/cache/provisioned.go`
2. **InstanceLimitsMap** (`impl/instances/limits.go`) → Provides storage limits
3. **Price Cache** (`priceCache.json`) → Combined pricing + limits data
4. **Calculation Logic** (`impl/price/provisioned.go`) → Uses cache for cost estimates

## How Storage Limits Are Used

Storage limits are checked in `impl/price/provisioned.go` at **line 144-154** in the `GetRequiredNodeCount` function:

```go
instanceLimits, found := instances.InstanceLimitsMap[iu.InstanceType]
if found && instanceLimits.IsStorageClassSupported(storageClass) {
    maxStoragePerNode := instanceLimits.GetMaxEbsVolume(storageClass)
    if maxStoragePerNode > 0 && maxStoragePerNode < storagePerNode {
        tempNodeCount := int(math.Ceil(float64(storage) / float64(maxStoragePerNode)))
        nodeCount = getNodeCountAlignedToAz(tempNodeCount, azs)
        storagePerNode = storage / nodeCount
    }
} else {
    return -1, -1  // Instance type not supported
}
```

This ensures:
- Storage per node doesn't exceed the instance's maximum EBS volume size
- Node count increases if required to stay within storage limits
- Unsupported instance types return error (-1)

## Testing

After adding new instance types, test with the estimate endpoints:

```bash
curl -X POST http://localhost:8080/provisioned/estimate \
  -H "Content-Type: application/json" \
  -d '{
    "region": "US East (N. Virginia)",
    "instanceType": "r8g.large.search",
    "nodeCount": 3,
    "storagePerNode": 1000,
    "storageClass": "gp3"
  }'
```

Verify:
- Storage calculations are correct
- Node count increases if storage exceeds `MaximumGp3`
- Pricing is returned from AWS data

## Troubleshooting

### Issue: Storage limits still empty after cache regeneration

**Cause:** Cache invalidation endpoint was called without `?update=true` parameter

**Solution:**
```bash
# Wrong (doesn't regenerate):
curl -X POST http://localhost:8080/provisioned/cache/invalidate

# Correct (regenerates):
curl -X POST "http://localhost:8080/provisioned/cache/invalidate?update=true"
```

### Issue: Instance type returns -1 node count

**Causes:**
1. Instance type not in `InstanceLimitsMap`
2. Storage class not supported (e.g., requesting GP2 for m7g/m8g instances)
3. `priceCache.json` not regenerated after adding to `InstanceLimitsMap`

**Solution:** Verify the instance is in `InstanceLimitsMap` and supports the requested storage class, then regenerate cache.

### Issue: Service build fails with "undefined: instances.InstanceLimitsMap"

**Cause:** Import path or package declaration incorrect

**Solution:** Ensure `impl/instances/limits.go` has:
```go
package instances

var InstanceLimitsMap = map[string]InstanceLimits{
    // ...
}
```

## Instance Family Patterns

Instance families are automatically detected using regex patterns in `impl/instances/instance.go`. If adding a completely new family, you may need to update `FamilyPatterns`:

```go
var FamilyPatterns = map[string]string{
    `^r\d+g`:  MemoryOptimized,      // r7g, r8g
    `^m\d+g`:  GeneralPurpose,       // m7g, m8g
    `^c\d+g`:  ComputeOptimized,     // c7g, c8g
    // Add new patterns if needed
}
```

## Production Deployment

After testing locally:

1. Commit changes to `impl/instances/limits.go` and `impl/cache/provisioned.go`
2. Deploy using the CDK deployment script:
   ```bash
   cd cdk-deployment
   ./deploy.sh
   ```
3. The production cache will be regenerated automatically on first request

## Related Files

- `impl/instances/limits.go` - Instance limits configuration (⚙️ MANUAL UPDATES)
- `impl/cache/provisioned.go` - Price cache loader (✅ ALREADY FIXED for r8g)
- `impl/price/provisioned.go` - Storage validation logic
- `priceCache.json` - Generated cache file (regenerated via API)
- `impl/instances/instance.go` - Instance family patterns

## Summary

Adding new instance types requires:
1. ✅ Add to `InstanceLimitsMap` in `limits.go`
2. ✅ Rebuild service
3. ✅ Regenerate price cache with `?update=true`
4. ✅ Verify storage limits populated in cache
5. ✅ Test cost estimates for new instance types

The October 2025 fix ensures that `InstanceLimitsMap` is always consulted, even when AWS pricing API doesn't provide Storage information.
