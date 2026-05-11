# Target pack: Amazon OpenSearch Service (AOS — managed domains)

AWS runs the cluster. The user gets a domain endpoint, IAM controls
access, and snapshots go to S3 buckets the user owns.

## Capability profile

| Capability         | Supported | Notes                                      |
| ------------------ | --------- | ------------------------------------------ |
| snapshot_restore   | yes       | Manual S3 repo, IAM passrole required.     |
| snapshot_repo_s3   | yes       | Only S3, only via signed-request register. |
| snapshot_repo_fs   | no        | No filesystem access.                      |
| reindex_from_remote| yes       | Source must be allowlisted in domain config. |
| painless_scripts   | yes       | Inline + stored.                           |
| custom_analyzers   | partial   | Bundled language plugins; no arbitrary jars. |
| security_plugin    | partial   | Fine-grained access control (FGAC); not the same UI as the OSS plugin. |
| ism                | yes       | Bundled.                                   |
| alerting           | yes       | Bundled.                                   |
| knn                | yes       | Bundled.                                   |
| ml_commons         | yes       | On supported instance types.               |

## Pre-flight (before any restore)

1. Domain must allow the user's IAM role to invoke
   `es:ESHttp*` / `es:HttpPost` on the snapshot endpoints.
2. An S3 bucket in the same region as the domain.
3. An IAM role the domain can assume to read the bucket. Trust
   policy: `es.amazonaws.com`. Permissions: `s3:GetObject`,
   `s3:ListBucket`, `s3:PutObject`, `s3:DeleteObject` on the bucket.
4. The user's role needs `iam:PassRole` for that snapshot role.

## Snapshot repo registration (signed)

This is the part that bites everyone. AWS requires the registration
request itself to be SigV4-signed. Plain curl with basic auth does
NOT work — you need either:

- The Python `requests-aws4auth` snippet, OR
- The `awscurl` tool, OR
- The MA orchestrator (it handles this).

Shape:

```
PUT https://<domain>/_snapshot/<repo-name>
{
  "type": "s3",
  "settings": {
    "bucket": "<bucket>",
    "region": "<region>",
    "role_arn": "arn:aws:iam::<account>:role/<snapshot-role>"
  }
}
```

After registration, restore is plain HTTPS.

## Restore from a self-managed source

The cross-engine snapshot path requires a "transfer" bucket: take
snapshot on source's S3, register the same bucket on AOS, restore.
Both clusters need IAM access to the bucket.

If the source is on-prem with no S3 access, RFS over HTTP is the
practical choice.

## Pitfalls

- **Major-version-skip restore is rejected.** AOS will refuse to
  restore a snapshot taken from a cluster more than 1 major version
  behind the domain. Bridge with an intermediate cluster, or use RFS.
- **Service-linked roles**: `AmazonOpenSearchServiceRolePolicy` must
  exist in the account, or domain creation will silently lack
  permissions. Auto-created on first domain create in modern accounts.
- **VPC-only domains** can't pull from public source URLs. RFS must
  run inside the VPC (Fargate, EC2 in the same subnet, etc.).
- **Index limits**: AOS caps shards-per-node based on instance type.
  Restoring a source with thousands of small shards will fail. Pre-shrink
  on the source, or accept reindex into fewer shards.
