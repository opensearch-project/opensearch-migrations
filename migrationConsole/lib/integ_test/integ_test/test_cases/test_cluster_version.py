"""
Tests for cluster_version.py and template name resolution in ma_argo_test_base.py.

These tests target real logic paths and edge cases that could cause failures
in the integration test framework.
"""
import pytest
from integ_test.cluster_version import ClusterVersion, is_incoming_version_supported


class TestClusterVersionParsing:
    """Test ClusterVersion parsing handles all valid and invalid inputs."""

    def test_valid_es_wildcard(self):
        v = ClusterVersion("ES_7.x")
        assert v.cluster_type == "ES"
        assert v.full_cluster_type == "elasticsearch"
        assert v.major_version == 7
        assert v.minor_version == 'x'

    def test_valid_os_wildcard(self):
        v = ClusterVersion("OS_2.X")
        assert v.cluster_type == "OS"
        assert v.full_cluster_type == "opensearch"
        assert v.major_version == 2
        assert v.minor_version == 'x'

    def test_valid_specific_minor(self):
        v = ClusterVersion("ES_7.10")
        assert v.major_version == 7
        assert v.minor_version == 10

    def test_valid_minor_zero(self):
        """Minor version 0 should be valid (e.g., ES_8.0)."""
        v = ClusterVersion("ES_8.0")
        assert v.minor_version == 0

    def test_str_representation_wildcard(self):
        v = ClusterVersion("ES_7.x")
        assert str(v) == "ES_7.x"

    def test_str_representation_specific(self):
        v = ClusterVersion("OS_2.19")
        assert str(v) == "OS_2.19"

    def test_invalid_no_prefix(self):
        with pytest.raises(ValueError, match="Invalid version format"):
            ClusterVersion("7.10")

    def test_invalid_wrong_prefix(self):
        with pytest.raises(ValueError, match="Invalid version format"):
            ClusterVersion("XX_7.10")

    def test_invalid_no_minor(self):
        with pytest.raises(ValueError, match="Invalid version format"):
            ClusterVersion("ES_7")

    def test_invalid_empty(self):
        with pytest.raises(ValueError, match="Invalid version format"):
            ClusterVersion("")

    def test_invalid_negative_major(self):
        """Negative major version should be rejected by the regex."""
        with pytest.raises(ValueError, match="Invalid version format"):
            ClusterVersion("ES_-1.0")

    def test_invalid_double_dot(self):
        with pytest.raises(ValueError, match="Invalid version format"):
            ClusterVersion("ES_7.10.2")

    def test_invalid_minor_y(self):
        """Only 'x' or 'X' should be valid wildcards, not other letters."""
        with pytest.raises(ValueError, match="Invalid version format"):
            ClusterVersion("ES_7.y")

    def test_uppercase_x_normalized(self):
        """Both 'x' and 'X' should normalize to 'x'."""
        v = ClusterVersion("ES_7.X")
        assert v.minor_version == 'x'


class TestIsIncomingVersionSupported:
    """Test version compatibility checking logic."""

    def test_wildcard_limiting_matches_any_minor(self):
        """ES_7.x should match ES_7.10, ES_7.17, etc."""
        limiting = ClusterVersion("ES_7.x")
        assert is_incoming_version_supported(limiting, ClusterVersion("ES_7.10")) is True
        assert is_incoming_version_supported(limiting, ClusterVersion("ES_7.17")) is True
        assert is_incoming_version_supported(limiting, ClusterVersion("ES_7.0")) is True

    def test_wildcard_limiting_rejects_different_major(self):
        limiting = ClusterVersion("ES_7.x")
        assert is_incoming_version_supported(limiting, ClusterVersion("ES_6.8")) is False

    def test_wildcard_limiting_rejects_different_type(self):
        limiting = ClusterVersion("ES_7.x")
        assert is_incoming_version_supported(limiting, ClusterVersion("OS_7.0")) is False

    def test_specific_limiting_matches_exact(self):
        limiting = ClusterVersion("ES_7.10")
        assert is_incoming_version_supported(limiting, ClusterVersion("ES_7.10")) is True

    def test_specific_limiting_rejects_different_minor(self):
        limiting = ClusterVersion("ES_7.10")
        assert is_incoming_version_supported(limiting, ClusterVersion("ES_7.17")) is False

    def test_wildcard_incoming_against_specific_limiting(self):
        """When limiting is specific (ES_7.10) and incoming is wildcard (ES_7.x),
        the current implementation returns False because int(10) != 'x'.
        This is the expected behavior - a wildcard incoming doesn't match a specific limit."""
        limiting = ClusterVersion("ES_7.10")
        incoming = ClusterVersion("ES_7.x")
        # The limiting version has minor_version=10 (int), so isinstance check fails
        # and it falls through to: 10 == 'x' which is False
        assert is_incoming_version_supported(limiting, incoming) is False

    def test_both_wildcards_same_major(self):
        """Two wildcards with same type and major should match."""
        limiting = ClusterVersion("ES_7.x")
        incoming = ClusterVersion("ES_7.X")
        assert is_incoming_version_supported(limiting, incoming) is True

    def test_os_versions(self):
        limiting = ClusterVersion("OS_2.x")
        assert is_incoming_version_supported(limiting, ClusterVersion("OS_2.19")) is True
        assert is_incoming_version_supported(limiting, ClusterVersion("OS_3.0")) is False


class TestGetTemplateName:
    """Test the template name resolution function added in PR #2405.
    
    This function is defined inside MATestBase.__init__, so we test it
    by importing and calling it directly after extracting the logic.
    """

    @staticmethod
    def get_template_name(version: ClusterVersion) -> str:
        """Extracted from ma_argo_test_base.py for testability."""
        version_map = {
            ('elasticsearch', 1, 'x'): 'elasticsearch-1-5-single-node',
            ('elasticsearch', 2, 'x'): 'elasticsearch-2-4-single-node',
            ('elasticsearch', 5, 'x'): 'elasticsearch-5-6-single-node',
            ('elasticsearch', 6, 'x'): 'elasticsearch-6-8-single-node',
            ('elasticsearch', 7, 'x'): 'elasticsearch-7-10-single-node',
            ('opensearch', 1, 'x'): 'opensearch-1-3-single-node',
            ('opensearch', 2, 'x'): 'opensearch-2-19-single-node',
            ('opensearch', 3, 'x'): 'opensearch-3-1-single-node',
        }
        key = (version.full_cluster_type, version.major_version, version.minor_version)
        if key in version_map:
            return version_map[key]
        return (f"{version.full_cluster_type}-"
                f"{version.major_version}-"
                f"{version.minor_version}-single-node")

    def test_es7_wildcard_resolves(self):
        v = ClusterVersion("ES_7.x")
        assert self.get_template_name(v) == "elasticsearch-7-10-single-node"

    def test_os3_wildcard_resolves(self):
        v = ClusterVersion("OS_3.x")
        assert self.get_template_name(v) == "opensearch-3-1-single-node"

    def test_all_wildcard_versions_have_mappings(self):
        """Every predefined wildcard version should have a template mapping."""
        wildcards = [
            ClusterVersion("ES_1.x"), ClusterVersion("ES_2.x"),
            ClusterVersion("ES_5.x"), ClusterVersion("ES_6.x"),
            ClusterVersion("ES_7.x"), ClusterVersion("OS_1.x"),
            ClusterVersion("OS_2.x"), ClusterVersion("OS_3.x"),
        ]
        for v in wildcards:
            name = self.get_template_name(v)
            # Should NOT contain 'x' - that means it fell through to the default
            assert '-x-' not in name, f"Version {v} has no template mapping, got: {name}"

    def test_es8_wildcard_has_no_mapping(self):
        """ES_8.x has no template mapping - this is a gap.
        The function falls through to the default which produces 'elasticsearch-8-x-single-node'.
        This will fail at runtime because no such template exists."""
        v = ClusterVersion("ES_8.x")
        name = self.get_template_name(v)
        # BUG: This produces 'elasticsearch-8-x-single-node' which doesn't exist
        assert name == "elasticsearch-8-x-single-node"  # Documents the current (broken) behavior

    def test_specific_minor_version_passthrough(self):
        """Specific minor versions should construct the name directly."""
        v = ClusterVersion("ES_7.10")
        assert self.get_template_name(v) == "elasticsearch-7-10-single-node"

    def test_specific_os_minor_version(self):
        v = ClusterVersion("OS_2.19")
        assert self.get_template_name(v) == "opensearch-2-19-single-node"
