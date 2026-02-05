# Normalize auth config for cluster objects (source_cluster, target_cluster)
def normalizeClusterAuthConfig:
  if has("authConfig") then
    (if (.authConfig | has("basic")) then
      .basic_auth = (.authConfig.basic |
        if has("secretName") then
          .k8s_secret_name = .secretName | del(.secretName)
        else
          .
        end)
    elif (.authConfig | has("sigv4")) then
      .sigv4 = .authConfig.sigv4
    elif (.authConfig | has("mtls")) then
      .mtls_auth = .authConfig.mtls
    else
      .no_auth = {}
    end)
    | del(.authConfig)
  else
    .no_auth = {}
  end
  | del(.label, .proxy, .snapshotRepo);

def normalizeAllowInsecure:
  if has("allowInsecure") then
    .allow_insecure = .allowInsecure | del(.allowInsecure)
  else
    .
  end;

def normalizeSnapshotName:
  if has("snapshotName") then
    .snapshot_name = .snapshotName | del(.snapshotName)
  else
    .
  end;

def normalizeSnapshotRepo:
  if has("repoConfig") and (.repoConfig | has("repoName")) then
    .snapshot_repo_name = .repoConfig.repoName
  else
    .
  end;

# Normalize S3 config inside snapshot
def normalizeS3Config:
  (if has("s3RepoPathUri") then
    .repo_uri = .s3RepoPathUri | del(.s3RepoPathUri)
  else
    .
  end)
  | (if has("s3RoleArn") then
    .role = .s3RoleArn | del(.s3RoleArn)
  else
    .
  end)
  | del(.repoName, .useLocalStack);

def normalizeRepoConfig:
  if has("repoConfig") then
    .s3 = (.repoConfig |
      if has("awsRegion") then
        .aws_region = .awsRegion | del(.awsRegion)
      else
        .
      end
      | normalizeS3Config)
    | del(.repoConfig)
  elif has("s3") then
    .s3 |= normalizeS3Config
  else
    .
  end;

# Normalize cluster config (only for source_cluster and target_cluster)
def normalizeCluster:
  normalizeClusterAuthConfig | normalizeAllowInsecure;

# Normalize snapshot config
def normalizeSnapshot:
  normalizeSnapshotName | normalizeSnapshotRepo | normalizeRepoConfig | del(.label);

# Apply transformations to specific top-level keys
(if has("source_cluster") then .source_cluster |= normalizeCluster else . end)
| (if has("target_cluster") then .target_cluster |= normalizeCluster else . end)
| (if has("snapshot") then .snapshot |= normalizeSnapshot else . end)
