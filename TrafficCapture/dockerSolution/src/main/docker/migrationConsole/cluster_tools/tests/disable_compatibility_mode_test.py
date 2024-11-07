from src.tools.disable_compatibility_mode import main
from src.cluster_tools.utils import console_curl


def test_disable_compatibility_mode(env):
    """Test the disable_compatibility_mode function to ensure it disables compatibility mode."""

    # Call disable_compatibility_mode
    main(env, None)

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
