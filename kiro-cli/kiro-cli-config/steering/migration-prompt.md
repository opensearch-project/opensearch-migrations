# Migration Assistant Prompt

Use this prompt for operator-guided migrations with the Migration Assistant. Optimize for safe, reviewable progress rather than one-shot automation.

## Operating Principles

1. Verify source and target connectivity before proposing a migration run.
2. Show the current target state before any migration or cleanup.
3. Estimate snapshot/backfill duration before submit when enough sizing data is available.
4. Do not silently clear target indices, delete snapshots, or change AWS/OpenSearch domain configuration.
5. After completion, verify outcome with doc-count and index checks.

## Recommended Flow

1. Discover candidate source/target clusters.
2. Let the user choose discovered domains or provide custom endpoints.
3. Verify connectivity to both clusters.
4. Read S3/snapshot defaults from the EKS config map.
5. Show existing target indices and ask before any cleanup.
6. Calculate `podReplicas` guidance and a rough migration time estimate.
7. Generate workflow configuration.
8. Submit and monitor the migration.
9. Verify doc counts and target index state after completion.

## Auto-Discovery

For AWS-managed domains, discover domains across all regions:

```bash
for r in $(aws ec2 describe-regions --query 'Regions[].RegionName' --output text); do (s=$(aws opensearch list-domain-names --region "$r" --query 'DomainNames[].DomainName' --output text 2>/dev/null) && [ -n "$s" ] && printf "=== %s ===\n%s\n" "$r" "$s") & done; wait
```

## Choose Migration Type

After discovery, ask the user to choose one of these paths:

1. **Migrate between discovered AWS-managed domains**
   - present the discovered domains with regions
   - use SigV4 auth automatically
2. **Use custom endpoints**
   - for self-managed clusters or non-discovered endpoints
   - specify auth mode explicitly (`basic`, `sigv4`, or other supported config)

## Pre-Flight Checklist

- [ ] EKS cluster connected (`kubectl get pods -n ma`)
- [ ] Migration console running
- [ ] Source/target connectivity verified
- [ ] Target indices reviewed
- [ ] S3 snapshot role/config available
- [ ] Migration estimate reviewed with user

## Commands and References

Use `steering/workflow.md` for workflow CLI commands, safety rails, and migration estimation details.

For deeper product docs, prefer current repo docs first:

```bash
cat migrationConsole/README.md
cat docs/MigrationAsAWorkflow.md
cat orchestrationSpecs/README.md
```

Use the wiki for additional walkthroughs and troubleshooting context:

```bash
cat opensearch-migrations.wiki/Workflow-CLI-Overview.md
cat opensearch-migrations.wiki/Workflow-CLI-Getting-Started.md
cat opensearch-migrations.wiki/Backfill-Workflow.md
cat opensearch-migrations.wiki/Troubleshooting.md
```

## Ready-State Prompting

Once discovery is complete, summarize briefly and move to selection. Example:

- discovered domains / endpoints
- likely auth mode
- whether the migration console is reachable
- any blockers before config generation
