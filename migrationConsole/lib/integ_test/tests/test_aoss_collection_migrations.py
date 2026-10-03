import base64
import json

from integ_test.test_cases.aoss_collection_tests import (
    AOSS_COLLECTIONS,
    build_aoss_migration_config,
    encode_migration_config,
)


SNAPSHOT_NAME = "aoss-test-snapshot"
TARGET_ENDPOINTS = {
    "search": "https://search.example.com",
    "timeseries": "https://timeseries.example.com",
    "vector": "https://vector.example.com",
}


def _config():
    return build_aoss_migration_config(
        source_version="OS 1.3",
        snapshot_name=SNAPSHOT_NAME,
        s3_repo_uri="s3://snapshot-bucket/aoss/",
        s3_region="us-east-1",
        target_endpoints=TARGET_ENDPOINTS,
        s3_role_arn="arn:aws:iam::123456789012:role/snapshot-role",
    )


def test_builds_one_source_and_three_aoss_targets():
    config = _config()

    assert list(config["sourceClusters"]) == ["source"]
    assert set(config["targetClusters"]) == set(AOSS_COLLECTIONS)
    assert len(config["snapshotMigrationConfigs"]) == 3

    source = config["sourceClusters"]["source"]
    assert source["snapshotInfo"]["repos"]["default"]["s3RoleArn"] == (
        "arn:aws:iam::123456789012:role/snapshot-role"
    )
    assert source["snapshotInfo"]["snapshots"][SNAPSHOT_NAME]["config"] == {
        "externallyManagedSnapshotName": SNAPSHOT_NAME,
    }

    for target_name, target in config["targetClusters"].items():
        assert target["endpoint"] == TARGET_ENDPOINTS[target_name]
        assert target["authConfig"]["sigv4"] == {
            "region": "us-east-1",
            "service": "aoss",
        }


def test_migration_branches_share_snapshot_and_have_disjoint_allowlists():
    config = _config()
    allowlists = {}

    for migration in config["snapshotMigrationConfigs"]:
        assert migration["fromSource"] == "source"
        target_name = migration["toTarget"]
        migration_pass = migration["perSnapshotConfig"][SNAPSHOT_NAME][0]
        metadata_allowlist = migration_pass["metadataMigrationConfig"]["indexAllowlist"]
        backfill_allowlist = migration_pass["documentBackfillConfig"]["indexAllowlist"]
        assert metadata_allowlist == backfill_allowlist
        assert metadata_allowlist == AOSS_COLLECTIONS[target_name]["expected_indices"]
        allowlists[target_name] = set(metadata_allowlist)

    target_names = list(allowlists)
    for index, target_name in enumerate(target_names):
        for other_target in target_names[index + 1:]:
            assert allowlists[target_name].isdisjoint(allowlists[other_target])


def test_encoded_config_round_trips_as_json():
    config = _config()

    decoded = json.loads(base64.b64decode(encode_migration_config(config)))

    assert decoded == config
