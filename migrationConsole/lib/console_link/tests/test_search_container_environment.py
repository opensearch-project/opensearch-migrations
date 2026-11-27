import pytest

from tests.search_containers import Version, _environment_by_version


@pytest.mark.parametrize(
    "version",
    [
        Version("OPENSEARCH", 1, 3, 16),
        Version("OPENSEARCH", 2, 19, 1),
    ],
)
def test_opensearch_environment_disables_performance_analyzer(version):
    assert _environment_by_version(version)["DISABLE_PERFORMANCE_ANALYZER_AGENT_CLI"] == "true"
