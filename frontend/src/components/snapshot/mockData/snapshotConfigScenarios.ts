import {
  S3SnapshotSource,
  SnapshotConfig,
  SnapshotSourceType,
} from "@/generated/api/types.gen";

// Debug scenario mock data for SnapshotConfig
export const SNAPSHOT_CONFIG_SCENARIOS: Record<string, SnapshotConfig> = {
  s3Config: {
    snapshot_name: "my-s3-snapshot",
    repository_name: "my-s3-repo",
    index_allow: ["index1", "index2", "index3"],
    source: {
      type: "s3" as SnapshotSourceType,
      uri: "s3://my-bucket/snapshots",
      region: "us-east-1",
    } as S3SnapshotSource,
  },
  fsConfig: {
    snapshot_name: "my-fs-snapshot",
    repository_name: "my-fs-repo",
    index_allow: ["*"],
    source: {
      type: "filesytem" as SnapshotSourceType,
      path: "/path/to/snapshots",
      uri: "", // Adding this to satisfy the type system
      region: "", // Adding this to satisfy the type system
    } as S3SnapshotSource, // Using S3SnapshotSource but with a filesystem type
  },
  emptyConfig: {
    snapshot_name: "new-snapshot",
    repository_name: "default-repo",
    index_allow: [],
    source: {
      type: "s3" as SnapshotSourceType,
      uri: "",
      region: "us-west-2",
    } as S3SnapshotSource,
  },
};
