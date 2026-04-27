import pytest
from integ_test.cluster_version import ClusterVersion
from integ_test.test_cases.ma_argo_test_base import get_template_name


@pytest.mark.parametrize("version_str, expected_template", [
    # Wildcard versions map to canonical representative minor
    ("ES_1.x", "elasticsearch-1-5-single-node"),
    ("ES_2.x", "elasticsearch-2-4-single-node"),
    ("ES_5.x", "elasticsearch-5-6-single-node"),
    ("ES_6.x", "elasticsearch-6-8-single-node"),
    ("ES_7.x", "elasticsearch-7-10-single-node"),
    ("ES_8.x", "elasticsearch-8-19-single-node"),
    ("OS_1.x", "opensearch-1-3-single-node"),
    ("OS_2.x", "opensearch-2-19-single-node"),
    ("OS_3.x", "opensearch-3-1-single-node"),
    # Concrete versions pass through directly
    ("ES_7.10", "elasticsearch-7-10-single-node"),
    ("ES_6.8", "elasticsearch-6-8-single-node"),
    ("OS_2.19", "opensearch-2-19-single-node"),
])
def test_get_template_name(version_str, expected_template):
    version = ClusterVersion(version_str)
    assert get_template_name(version) == expected_template


def test_get_template_name_unknown_wildcard_raises():
    version = ClusterVersion("SOLR_8.x")
    with pytest.raises(ValueError, match="No template mapping for wildcard version"):
        get_template_name(version)


def test_combinations_resolve_to_templates():
    """Guards against drift between RFS/CDC combinations and the wildcard template map.

    Every version used in RFS_MIGRATION_COMBINATIONS and CDC_MIGRATION_COMBINATIONS
    must resolve via get_template_name(); otherwise integration tests against that
    combination will fail at cluster-provisioning time with a confusing error.
    """
    from integ_test.cluster_version import (
        RFS_MIGRATION_COMBINATIONS, CDC_MIGRATION_COMBINATIONS
    )
    all_versions = {v for combos in (RFS_MIGRATION_COMBINATIONS, CDC_MIGRATION_COMBINATIONS)
                    for pair in combos for v in pair}
    for v in all_versions:
        get_template_name(v)  # raises ValueError if unmapped


def test_template_names_exist_in_cluster_workflows():
    """Guards against drift between RFS/CDC combinations and clusterWorkflows.yaml.

    Every template name produced by get_template_name() for a version used in
    RFS/CDC combinations must be declared as an Argo template in
    clusterWorkflows.yaml; otherwise integration tests will fail at cluster
    provisioning with a 'no template named X' error.
    """
    import pathlib
    import yaml
    from integ_test.cluster_version import (
        RFS_MIGRATION_COMBINATIONS, CDC_MIGRATION_COMBINATIONS
    )
    yaml_path = pathlib.Path(__file__).parents[2] / "testWorkflows/clusterWorkflows.yaml"
    doc = yaml.safe_load(yaml_path.read_text())
    declared = {t["name"] for t in doc["spec"]["templates"]}
    for combos in (RFS_MIGRATION_COMBINATIONS, CDC_MIGRATION_COMBINATIONS):
        for src, tgt in combos:
            assert get_template_name(src) in declared, \
                f"Source template {get_template_name(src)} not declared in clusterWorkflows.yaml"
            assert get_template_name(tgt) in declared, \
                f"Target template {get_template_name(tgt)} not declared in clusterWorkflows.yaml"
