"""
This file contains all parameters and constants used across the large snapshot multiplication test.
These will be fetched from Jenkins pipeline parameters that users can configure.
"""

# =============================================================================
# JENKINS PARAMETERS
# =============================================================================

# Index and Document Configuration
INDEX_NAME = "basic_index"
NUM_SHARDS = 10  # Number of shards for test index
DOCS_PER_BATCH = 50  # Number of documents to ingest for testing
MULTIPLICATION_FACTOR = 10  # Total docs including original (1 original + 9 duplicates = 10 total per input doc)

# Performance Configuration
RFS_WORKERS = 5  # Number of RFS workers to scale to
BACKFILL_TIMEOUT_HOURS = 0.5  # Timeout in hours (30 minutes = 0.5 hours)
SNAPSHOT_TIMEOUT_MINUTES = 5  # Snapshot creation timeout in minutes

# AWS Configuration
SNAPSHOT_REGION = "us-west-2"
CLUSTER_VERSION = "es7x"  # Version family for snapshot structure

# S3 Bucket Prefixes and Suffixes
S3_BUCKET_URI_PREFIX = "s3://migration-artifacts-"
S3_BUCKET_SUFFIX = "/rfs-snapshot-repo/"
LARGE_SNAPSHOT_BUCKET_PREFIX = "migrations-jenkins-snapshot-"
LARGE_S3_DIRECTORY_PREFIX = "large-snapshot-"

# Role ARN Building Blocks
ROLE_ARN_PREFIX = "arn:aws:iam::"
ROLE_ARN_SUFFIX_TEMPLATE = ":role/OSMigrations-{stage}-{region}-default-SnapshotRole"

# File Paths
CONFIG_FILE_PATH = "/config/migration_services.yaml"
TEMP_CONFIG_FILE_PATH = "/config/migration_large_snapshot.yaml"
TRANSFORMATION_DIRECTORY = "/shared-logs-output/test-transformations"

# Fixed Identifiers
UNIQUE_ID = "large_snapshot_test"
SNAPSHOT_REPO_NAME = "migration_assistant_repo"
TEST_STAGE = "dev"

# Polling and Stability Configuration (in seconds)
SNAPSHOT_POLL_INTERVAL = 3
BACKFILL_POLL_INTERVAL = 5
STABILITY_CHECK_INTERVAL = 15
STABILITY_CHECK_COUNT = 4

# Command Execution
COMMAND_TIMEOUT_SECONDS = 300

# Test Document Template
INGEST_DOC = {
    "title": "Large Snapshot Migration Test Document"
}

# Legacy Constants (for backward compatibility)
EXPECTED_DOC_COUNT = 1  # Legacy - will be calculated dynamically

# Computed values using Jenkins parameters
INDEX_SHARD_COUNT = NUM_SHARDS
INGESTED_DOC_COUNT = DOCS_PER_BATCH
MULTIPLICATION_FACTOR_WITH_ORIGINAL = MULTIPLICATION_FACTOR
RFS_WORKER_COUNT = RFS_WORKERS
TEST_REGION = SNAPSHOT_REGION
VERSION_FAMILY = CLUSTER_VERSION
BACKFILL_TIMEOUT_MINUTES = int(BACKFILL_TIMEOUT_HOURS * 60)

# Computed S3 and ARN values
LARGE_SNAPSHOT_BUCKET_SUFFIX = f"-{SNAPSHOT_REGION}"
LARGE_S3_BASE_PATH = str(LARGE_S3_DIRECTORY_PREFIX + CLUSTER_VERSION)
ROLE_ARN_SUFFIX = ROLE_ARN_SUFFIX_TEMPLATE.format(stage=TEST_STAGE, region=SNAPSHOT_REGION)
TRANSFORMATION_FILE_PATH = f"{TRANSFORMATION_DIRECTORY}/transformation.json"
