import pytest
import unittest.mock as mock

import upgrade_testing_framework.cluster_management.node as node

def test_WHEN_create_NodeConfiguration_THEN_has_expected_values():
    # Set up our test
    test_node_name = "node-name"
    test_cluster_name = "cluster-name"
    test_master_nodes = ["m1", "m2"]
    test_seed_hosts = ["s1", "s2"]

    # Run our test
    actual_value = node.NodeConfiguration(test_node_name, test_cluster_name, test_master_nodes, test_seed_hosts)

    # Check the results
    expected_value = {
        "cluster.name": test_cluster_name,
        "cluster.initial_master_nodes": ",".join(test_master_nodes),
        "discovery.seed_hosts": ",".join(test_seed_hosts),
        "node.name": test_node_name,
        "bootstrap.memory_lock": "true",
        "ES_JAVA_OPTS": "-Xms512m -Xmx512m"
    }
    assert expected_value == actual_value.config

def test_WHEN_node_start_THEN_as_expected():
    # Set up our test
    test_container = mock.Mock()
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_docker_client.create_container.return_value = test_container
    test_node_config = mock.Mock()
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

def test_WHEN_node_stop_THEN_as_expected():
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
    expected_value = [mock.call(
        test_container
    )]
    assert expected_value == test_docker_client.stop_container.call_args_list

def test_WHEN_node_clean_up_THEN_as_expected():
    # Set up our test
    test_container = mock.Mock()
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client, test_container)
    test_node.clean_up()

    # Check the results
    expected_value = [mock.call(
        test_container
    )]
    assert expected_value == test_docker_client.remove_container.call_args_list

    assert None == test_node._container

def test_WHEN_node_is_active_AND_active_THEN_returns_true():
    # Set up our test
    test_container = mock.Mock()
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_docker_client.run.return_value = (0, "")
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client, test_container)
    actual_value = test_node.is_active()

    # Check the results
    expected_args = [mock.call(
        test_container,
        "curl -X GET \"localhost:9200/\""
    )]
    assert expected_args == test_docker_client.run.call_args_list

    assert True == actual_value

def test_WHEN_node_is_active_AND_inactive_THEN_returns_false():
    # Set up our test
    test_container = mock.Mock()
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_docker_client.run.return_value = (1, "")
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client, test_container)
    actual_value = test_node.is_active()

    # Check the results
    assert False == actual_value

def test_WHEN_node_is_active_AND_not_started_THEN_returns_false():
    # Set up our test
    test_container_config = mock.Mock()
    test_docker_client = mock.Mock()
    test_docker_client.run.return_value = (0, "") # Will cause True to be returned if not working
    test_node_config = mock.Mock()
    test_node_name = "node-name"

    # Run our test
    test_node = node.Node(test_node_name, test_container_config, test_node_config, test_docker_client)
    actual_value = test_node.is_active()

    # Check the results
    assert False == actual_value
