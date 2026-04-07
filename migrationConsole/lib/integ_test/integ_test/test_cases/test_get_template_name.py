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
    version = ClusterVersion("ES_8.x")
    with pytest.raises(ValueError, match="No template mapping for wildcard version"):
        get_template_name(version)
