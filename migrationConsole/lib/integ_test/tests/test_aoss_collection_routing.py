import re

from integ_test.test_cases.aoss_collection_routing_tests import (
    ROUTED_INDICES,
    RFS_POD_REPLICAS,
    build_collection_routing,
    build_routed_migration_config,
    collection_names,
    index_checks,
)


SNAPSHOT_NAME = "aoss-test-snapshot"
PREFIX = "aossr-p42"


def _config():
    return build_routed_migration_config(
        source_version="OS 1.3",
        snapshot_name=SNAPSHOT_NAME,
        s3_repo_uri="s3://snapshot-bucket/aoss/",
        s3_region="us-east-1",
        account_endpoint="https://123456789012.aoss.us-east-1.on.aws",
        collection_prefix=PREFIX,
    )


def _resolve(routing, index):
    """Mirrors ServerlessCollectionRouting: static match wins, then the first whole-name regex match."""
    for entry in routing.get("staticCollectionRouting", []):
        if entry["sourceIndex"] == index:
            return entry["collection"]
    for entry in routing.get("regexCollectionRouting", []):
        match = re.fullmatch(entry["sourceIndex"], index)
        if match:
            return re.sub(r"\$(\d+)", lambda g: match.group(int(g.group(1))), entry["collection"])
    return None


def test_builds_one_collection_routed_target():
    config = _config()

    assert list(config["targetClusters"]) == ["routed"]
    target = config["targetClusters"]["routed"]
    assert target["collectionRouted"] is True
    assert target["endpoint"] == "https://123456789012.aoss.us-east-1.on.aws"
    assert target["authConfig"]["sigv4"] == {"region": "us-east-1", "service": "aoss"}


def test_single_migration_carries_routing_and_every_index():
    config = _config()

    assert len(config["snapshotMigrationConfigs"]) == 1
    migration_pass = config["snapshotMigrationConfigs"][0]["perSnapshotConfig"][SNAPSHOT_NAME][0]
    all_indices = [index for indices in ROUTED_INDICES.values() for index in indices]
    routing = build_collection_routing(PREFIX)
    assert migration_pass["staticCollectionRouting"] == routing["staticCollectionRouting"]
    assert migration_pass["regexCollectionRouting"] == routing["regexCollectionRouting"]
    assert "collectionRouting" not in migration_pass
    assert migration_pass["metadataMigrationConfig"]["indexAllowlist"] == all_indices
    assert migration_pass["documentBackfillConfig"]["indexAllowlist"] == all_indices
    assert migration_pass["documentBackfillConfig"]["podReplicas"] == RFS_POD_REPLICAS


def test_routing_table_sends_each_index_to_its_expected_collection():
    routing = build_collection_routing(PREFIX)
    names = collection_names(PREFIX)

    for suffix, indices in ROUTED_INDICES.items():
        for index in indices:
            assert _resolve(routing, index) == names[suffix]
    assert names == {"search": f"{PREFIX}-search", "vectors": f"{PREFIX}-vectors"}


def test_every_routed_index_reuses_test0021_checks():
    for indices in ROUTED_INDICES.values():
        for index in indices:
            assert index_checks(index), f"{index} has no Test0021 checks to reuse"
