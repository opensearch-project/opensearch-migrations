"""
Shared constants for large snapshot test suite.
This file contains all global variables and configuration constants
used across the three-phase large snapshot migration test.
"""

# File Paths
TEMP_CONFIG_FILE_PATH = "/config/migration_large_snapshot.yaml"
CONFIG_FILE_PATH = "/config/migration_services.yaml"

# Test Configuration
UNIQUE_ID = "large_snapshot_test"
INDEX_NAME = "basic_index"
INDEX_SHARD_COUNT = 10  # Number of shards for test index
INGESTED_DOC_COUNT = 50  # Number of documents to ingest for testing
EXPECTED_DOC_COUNT = 1  # Legacy - will be calculated dynamically
TEST_REGION = "us-west-2"
TEST_STAGE = "dev"

# Timeout Configuration (in minutes)
SNAPSHOT_TIMEOUT_MINUTES = 5
BACKFILL_TIMEOUT_MINUTES = 30
COMMAND_TIMEOUT_SECONDS = 300

# Polling Intervals (in seconds)
SNAPSHOT_POLL_INTERVAL = 3
BACKFILL_POLL_INTERVAL = 5
STABILITY_CHECK_INTERVAL = 15
STABILITY_CHECK_COUNT = 4

# Test Document Template
TEST_DOCUMENT = {
    "title": "Large Snapshot Migration Test Document"
}

# S3 Configuration for cleanup - uses dynamic account ID
S3_BUCKET_URI_PREFIX = "s3://migration-artifacts-"
S3_BUCKET_SUFFIX = "/rfs-snapshot-repo/"
SNAPSHOT_REPO_NAME = "migration_assistant_repo"

# Large Snapshot Configuration - uses dynamic account ID and region
LARGE_SNAPSHOT_BUCKET_PREFIX = "migrations-jenkins-snapshot-"
LARGE_SNAPSHOT_BUCKET_SUFFIX = f"-{TEST_REGION}"
VERSION_FAMILY = "es7x"
LARGE_S3_DIRECTORY_PREFIX = "large-snapshot-"
LARGE_S3_BASE_PATH = str(LARGE_S3_DIRECTORY_PREFIX + VERSION_FAMILY)

# Role ARN Configuration - uses dynamic account ID
ROLE_ARN_PREFIX = "arn:aws:iam::"
ROLE_ARN_SUFFIX = f":role/OSMigrations-{TEST_STAGE}-{TEST_REGION}-default-SnapshotRole"

# RFS Configuration
RFS_WORKER_COUNT = 5  # Number of RFS workers to scale to

# Transformation Configuration
# Total docs including original (1 original + 9 duplicates = 10 total per input doc)
MULTIPLICATION_FACTOR_WITH_ORIGINAL = 10
TRANSFORMATION_DIRECTORY = "/shared-logs-output/test-transformations"
TRANSFORMATION_FILE_PATH = "/shared-logs-output/test-transformations/transformation.json"

# Phase Status Files (for inter-test communication)
PHASE_STATUS_DIR = "/shared-logs-output/phase-status"
PREPARE_COMPLETE_FILE = f"{PHASE_STATUS_DIR}/prepare_complete.flag"
COOK_COMPLETE_FILE = f"{PHASE_STATUS_DIR}/cook_complete.flag"
SERVE_COMPLETE_FILE = f"{PHASE_STATUS_DIR}/serve_complete.flag"

# Shared State Files (for passing data between phases)
SHARED_STATE_DIR = "/shared-logs-output/shared-state"
FINAL_COUNT_FILE = f"{SHARED_STATE_DIR}/final_count.txt"
SNAPSHOT_URI_FILE = f"{SHARED_STATE_DIR}/snapshot_uri.txt"
