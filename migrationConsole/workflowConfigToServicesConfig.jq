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

# Map the unified repoPathUri to console_link's repo_uri.
def normalizeRepoUri:
  if has("repoPathUri") then
    .repo_uri = .repoPathUri | del(.repoPathUri)
  else
    .
  end;

# S3-side enrichment: awsRegion → aws_region, s3RoleArn → role.
def normalizeS3Config:
  normalizeRepoUri
  | (if has("awsRegion") then
      .aws_region = .awsRegion | del(.awsRegion)
    else
      .
    end)
  | (if has("s3RoleArn") then
      .role = .s3RoleArn | del(.s3RoleArn)
    else
      .
    end)
  | del(.repoName, .useLocalStack);

# GCS has no extra fields beyond repoPathUri/endpoint at the moment.
def normalizeGcsConfig:
  normalizeRepoUri
  | del(.repoName, .useLocalStack, .awsRegion, .s3RoleArn);

def normalizeRepoConfig:
  if has("repoConfig") then
    (if (.repoConfig.repoPathUri // "" | startswith("gs://")) then
      .gcs = (.repoConfig | normalizeGcsConfig)
    else
      .s3 = (.repoConfig | normalizeS3Config)
    end)
    | del(.repoConfig)
  elif has("s3") then
    # Legacy path: services config already in console_link shape with top-level s3.
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
