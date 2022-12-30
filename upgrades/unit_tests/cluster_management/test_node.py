import pytest
import unittest.mock as mock

import upgrade_testing_framework.cluster_management.node as node
import upgrade_testing_framework.cluster_management.docker_framework_client as dfc


def test_WHEN_node_start_THEN_as_expected():
    # Set up our test
    test_container = mock.Mock()
    test_container_config = mock.Mock()
    test_container_config.rest_port = 9200
    test_mount_point = "/my/path"
    test_docker_volume = dfc.DockerVolume(test_mount_point, mock.Mock())
    test_container_config.volumes = [test_docker_volume]

    test_docker_client = mock.Mock()
    test_docker_client.create_container.return_value = test_container
    test_node_user = "user"
    test_node_config = mock.Mock()
    test_node_config.user = test_node_user
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client)
    test_node.start()

    # Check the results
    expected_value = [mock.call(
        test_container_config.image,
        test_node_name,
        test_container_config.network,
        test_container_config.port_mappings,
        test_container_config.volumes,
        test_container_config.ulimits,
        test_node_config.config
    )]
    assert expected_value == test_docker_client.create_container.call_args_list

    assert test_container == test_node._container
    assert 9200 == test_node.rest_port

    expected_ownership_calls = [mock.call(
        test_container,
        test_node_user,
        test_mount_point
    )]
    assert expected_ownership_calls == test_docker_client.set_ownership_of_directory.call_args_list

    assert node.STATE_RUNNING == test_node._node_state


def test_WHEN_node_start_AND_already_running_THEN_no_op():
    # Set up our test
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client)
    test_node._node_state = node.STATE_RUNNING
    test_node.start()

    # Check the results
    expected_create_calls = []
    assert expected_create_calls == test_docker_client.create_container.call_args_list

    expected_ownership_calls = []
    assert expected_ownership_calls == test_docker_client.set_ownership_of_directory.call_args_list


def test_WHEN_node_start_AND_stopped_THEN_raises():
    # Set up our test
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client)
    test_node._node_state = node.STATE_STOPPED

    with pytest.raises(node.NodeRestartNotAllowedException):
        test_node.start()


def test_WHEN_node_start_AND_cleaned_THEN_raises():
    # Set up our test
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client)
    test_node._node_state = node.STATE_CLEANED

    with pytest.raises(node.NodeRestartNotAllowedException):
        test_node.start()


def test_WHEN_node_stop_THEN_as_expected():
    # Set up our test
    test_container = mock.Mock()
    test_container_config = mock.Mock()
    test_container_config.volumes = []
    test_docker_client = mock.Mock()
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client, test_container)
    test_node._node_state = node.STATE_RUNNING
    test_node.stop()

    # Check the results
    expected_stop_calls = [mock.call(
        test_container
    )]
    assert expected_stop_calls == test_docker_client.stop_container.call_args_list
    assert node.STATE_STOPPED == test_node._node_state


def test_WHEN_node_stop_AND_not_started_THEN_no_op():
    # Set up our test
    test_container = mock.Mock()
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client, test_container)
    test_node.stop()

    # Check the results
    expected_stop_calls = []
    assert expected_stop_calls == test_docker_client.stop_container.call_args_list


def test_WHEN_node_clean_up_THEN_as_expected():
    # Set up our test
    test_container = mock.Mock()
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client, test_container)
    test_node._node_state = node.STATE_STOPPED
    test_node.clean_up()

    # Check the results
    expected_value = [mock.call(
        test_container
    )]
    assert expected_value == test_docker_client.remove_container.call_args_list

    assert None is test_node._container
    assert node.STATE_CLEANED == test_node._node_state


def test_WHEN_node_clean_up_AND_not_stopped_THEN_raises():
    # Set up our test
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client)

    with pytest.raises(node.NodeNotStoppedException):
        test_node.clean_up()


def test_WHEN_node_is_active_AND_active_THEN_returns_true():
    # Set up our test
    test_container = mock.Mock()
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_docker_client.run_command.return_value = (0, "")
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client, test_container)
    test_node._node_state = node.STATE_RUNNING
    actual_value = test_node.is_active()

    # Check the results
    expected_args = [mock.call(
        test_container,
        "curl -X GET \"localhost:9200/\""
    )]
    assert expected_args == test_docker_client.run_command.call_args_list

    assert True is actual_value


def test_WHEN_node_is_active_AND_inactive_THEN_returns_false():
    # Set up our test
    test_container = mock.Mock()
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_docker_client.run_command.return_value = (1, "")
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client, test_container)
    actual_value = test_node.is_active()

    # Check the results
    assert False is actual_value


def test_WHEN_node_is_active_AND_not_started_THEN_returns_false():
    # Set up our test
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_docker_client.run_command.return_value = (0, "")  # Will cause True to be returned if not working
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client)
    actual_value = test_node.is_active()

    # Check the results
    assert False is actual_value
