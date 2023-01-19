from pathlib import Path
import unittest.mock as mock

import cluster_migration_core.robot_actions.cluster_action_executor as cae


@mock.patch("cluster_migration_core.robot_actions.cluster_action_executor.robot")
def test_WHEN_execute_called_THEN_invokes_my_implementation(mock_robot):
    # Test values
    hostname = "thingol"
    port = "1"
    engine_version = "AABB_1_2_3"
    actions_dir = Path("/actions")
    include_tags = ["include"]
    exclude_tags = ["exclude"]
    output_dir = Path("/output")
    console_width = "42"
    log_level = "CRITICAL"

    test_executor = cae.ClusterActionExecutor(
        hostname,
        port,
        engine_version,
        actions_dir,
        output_dir,
        include_tags=include_tags,
        exclude_tags=exclude_tags,
        console_width=console_width,
        log_level=log_level,
    )

    # Run our test
    test_executor.execute()

    # Check the results
    expected_calls = [mock.call(
        actions_dir,
        include=include_tags,
        exclude=exclude_tags,
        outputdir=output_dir,
        variable=[f"host:{hostname}", f"port:{port}", f"engine_version:{engine_version}"],
        consolewidth=console_width,
        loglevel=log_level
    )]
    assert expected_calls == mock_robot.run.call_args_list
