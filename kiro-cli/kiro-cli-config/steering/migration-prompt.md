# Migration Assistant Prompt (Default)

The default workflow for this agent is the SOP shipped with the assistant package:

- `.kiro/steering/opensearch-migration-assistant-eks.sop.md`

This file is intentionally short and only provides quick-start prompts. Use the SOP as the source of truth.

## First Question (Required)

Ask for `hands_on_level` up front:

```text
hands_on_level: guided | semi_auto | auto
```

## Environment Selection

Support both:

1) Deploy a new MA EKS stage (CloudFormation/CDK; requires explicit confirmation)
2) Use an existing MA EKS stage by reading CloudFormation exports (`MigrationsExportString*`)

## Cluster Selection

Then choose:

1) `aws_discover` (discover Amazon OpenSearch Service domains across regions)
2) `custom` (user provides endpoints + auth)

Discovery snippet (reference):

```bash
for r in $(aws ec2 describe-regions --query 'Regions[].RegionName' --output text); do (s=$(aws opensearch list-domain-names --region "$r" --query 'DomainNames[].DomainName' --output text 2>/dev/null) && [ -n "$s" ] && printf "=== %s ===\n%s\n" "$r" "$s") & done; wait
```

## Quick Start Prompt Template

```text
hands_on_level: guided

ma_environment:
  mode: use_existing_stage
  stage: <stage>
  region: <region>

clusters:
  source: aws_discover
  target: aws_discover

indices: all
```
