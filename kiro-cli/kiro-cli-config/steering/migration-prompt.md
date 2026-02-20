# Migration Assistant Prompt

## Auto-Discovery

Let me first discover your AWS managed OpenSearch domains across all regions...

```bash
for r in $(aws ec2 describe-regions --query 'Regions[].RegionName' --output text); do (s=$(aws opensearch list-domain-names --region "$r" --query 'DomainNames[].DomainName' --output text 2>/dev/null) && [ -n "$s" ] && printf "=== %s ===\n%s\n" "$r" "$s") & done; wait
```

## Choose Migration Type

After discovering your domains, I'll ask:

1. **Migrate between discovered AWS managed domains** (recommended)
   - I'll show you all discovered domains with their regions
   - You select source and target from the list
   - Uses SigV4 authentication automatically

2. **Use custom endpoints** (for self-managed clusters)
   - Provide source/target endpoints manually
   - Specify authentication (basic auth or SigV4)

## What I'll Do

1. **Auto-discover domains** - Scan all AWS regions for OpenSearch domains
2. **Present options** - Show discovered domains or ask for custom endpoints
3. **Verify connectivity** - Test both clusters are reachable
4. **Get S3/snapshot config** - From EKS configmap
5. **Check target state** - Show existing indices, ask before proceeding
6. **Calculate sizing** - Estimate podReplicas and migration time
7. **PRESENT ESTIMATE** - Show snapshot time + backfill time, ask user to confirm before proceeding
8. **Generate config** - Create workflow configuration
9. **Submit & monitor** - Run migration with progress updates
10. **Verify completion** - Compare doc counts source vs target

## Pre-Flight Checklist (I'll handle this)

- [ ] EKS cluster connected (`kubectl get pods -n ma`)
- [ ] Migration console running
- [ ] Source/target connectivity verified
- [ ] Target indices reviewed (clear if needed)
- [ ] S3 snapshot role has access to source cluster

## Ready?

I'll start by discovering your AWS OpenSearch domains automatically - no initial input needed!
