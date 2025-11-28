from cluster_tools.tools.disable_compatibility_mode import main as disable_compatibility_mode
from src.cluster_tools.base.utils import console_curl
import logging

logger = logging.getLogger(__name__)


def test_disable_compatibility_mode(env):
    """Test the disable_compatibility_mode function to ensure it disables compatibility mode."""

    disable_compatibility_mode(env, None)

    # Verify that the compatibility mode setting is now disabled
    cluster_settings = console_curl(
        env,
        path="/_cluster/settings",
        cluster='target_cluster',
        method='GET'
    )

    assert isinstance(cluster_settings, dict), "Failed to retrieve cluster settings."
    assert cluster_settings.get("persistent", {}).get("compatibility", {}) \
        .get("override_main_response_version", {}) == 'false', \
        "Compatibility mode was not disabled."
