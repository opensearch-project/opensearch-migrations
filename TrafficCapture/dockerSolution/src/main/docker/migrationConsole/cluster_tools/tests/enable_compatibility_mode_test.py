from src.tools.enable_compatibility_mode import main
from src.cluster_tools.utils import console_curl


def test_enable_compatibility_mode(env):
    """Test the enable_compatibility_mode function to ensure it enables compatibility mode."""

    # Call enable_compatibility_mode
    main(env, None)

    # Verify that the compatibility mode setting is now enabled
    cluster_settings = console_curl(
        env,
        path="/_cluster/settings",
        cluster='target_cluster',
        method='GET'
    )

    assert isinstance(cluster_settings, dict), "Failed to retrieve cluster settings."
    assert cluster_settings.get("persistent", {}).get("compatibility", {}) \
        .get("override_main_response_version", {}) == 'true', \
        "Compatibility mode was not enabled."
