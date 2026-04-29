# Safety Rules

## Destructive Action Rules

These operations require explicit user confirmation regardless of interaction mode:

- Deleting indices on source or target
- Clearing index templates
- Deleting snapshots or snapshot repositories
- Modifying AWS domain configuration (access policies, security groups, IAM)
- Deleting S3 objects
- Running `aws opensearch update-domain-config`
- Running `aws ec2 authorize-security-group-*` or `revoke-security-group-*`
- Running `aws iam` role/policy modifications

**Never delete S3 snapshot files directly.** Delete snapshots via the cluster API first.

## Cluster Access Rules

- All source/target cluster API calls go through the Migration Console using
  `console clusters curl`. Never use `kubectl port-forward`, `kubectl exec`
  into source pods, or direct `curl` to cluster endpoints.
- The Migration Console handles auth, TLS, and network routing correctly.
- `kubectl port-forward` does not work in many environments (CloudShell, CI/CD).

## AWS Change Rules

If resolving an issue requires AWS-side changes (access policies, security groups,
IAM, domain config):
- Provide the exact command to the user
- Ask the user to run it or explicitly approve it
- Never run these automatically

## Interaction Modes

| Mode | Phase boundaries | Within phases | Destructive actions |
|---|---|---|---|
| **guided** | Ask confirmation | Ask confirmation | Always ask |
| **semi_auto** | Ask confirmation | Proceed automatically | Always ask |
| **auto** | Proceed automatically | Proceed automatically | Ask unless `allow_destructive=true` |

Default to `guided` if the interaction level is unclear.

## Retry Rules

- Do not retry the same failing approach more than 3 times.
- After 3 failures, stop and report to the user with a summary of what was tried.
- Propose alternative approaches.
