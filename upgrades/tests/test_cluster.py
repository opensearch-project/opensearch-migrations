import pytest
import unittest.mock as mock

import upgrade_testing_framework.cluster_management.cluster as cluster

def test_WHEN_create_Cluster_AND_not_enough_nodes_THEN_raises():
    # Set up test
    test_cluster_name = "cluster-name"
    test_num_nodes = 0 # must be > 1
    mock_docker_client = mock.Mock()
    test_image = "image"

    # Run our test
    with pytest.raises(ValueError):
        cluster.Cluster(test_cluster_name, test_num_nodes, mock_docker_client, test_image)

@mock.patch('upgrade_testing_framework.cluster_management.cluster.Node')
def test_WHEN_start_THEN_as_expected(mock_node_class):
    # Set up test
    test_cluster_name = "cluster-name"
    test_num_nodes = 2
    mock_docker_client = mock.Mock()
    test_image = "image"

    mock_network = mock.Mock()
    mock_volume_1 = mock.Mock()
    mock_volume_2 = mock.Mock()
    mock_docker_client.create_network.return_value = mock_network
    mock_docker_client.create_volume.side_effect = [mock_volume_1, mock_volume_2]

    mock_node_1 = mock.Mock()
    mock_node_2 = mock.Mock()
    mock_node_class.side_effect = [mock_node_1, mock_node_2]

    test_cluster = cluster.Cluster(test_cluster_name, test_num_nodes, mock_docker_client, test_image)

    # Run our test
    test_cluster.start()

    # Check the results
    assert [mock_network] == test_cluster._networks
    assert [mock_volume_1, mock_volume_2] == test_cluster._volumes
    assert {
        test_cluster._generate_node_name(1): mock_node_1,
        test_cluster._generate_node_name(2): mock_node_2
        } == test_cluster._nodes
    assert mock_node_1.start.called
    assert mock_node_2.start.called

@mock.patch('upgrade_testing_framework.cluster_management.cluster.time')
def test_WHEN_wait_for_cluster_to_start_up_THEN_as_expected(mock_time):
    # Set up test
    test_cluster_name = "cluster-name"
    test_num_nodes = 2
    mock_docker_client = mock.Mock()
    test_image = "image"

    mock_node_1 = mock.Mock()
    mock_node_1.is_active.side_effect = [False, False, True]
    mock_node_2 = mock.Mock()
    mock_node_2.is_active.side_effect = [False, True]

    test_cluster = cluster.Cluster(test_cluster_name, test_num_nodes, mock_docker_client, test_image)
    test_cluster._nodes = {
        test_cluster._generate_node_name(1): mock_node_1,
        test_cluster._generate_node_name(2): mock_node_2
    }

    # Run our test
    test_cluster.wait_for_cluster_to_start_up(10)

    # Check the results
    assert 2 == mock_time.sleep.call_count
    assert 3 == mock_node_1.is_active.call_count
    assert 2 == mock_node_2.is_active.call_count

@mock.patch('upgrade_testing_framework.cluster_management.cluster.time')
def test_WHEN_wait_for_cluster_to_start_up_AND_max_wait_exceeded_THEN_raises(mock_time):
    # Set up test
    test_cluster_name = "cluster-name"
    test_num_nodes = 2
    mock_docker_client = mock.Mock()
    test_image = "image"

    mock_node_1 = mock.Mock()
    mock_node_1.is_active.side_effect = [False, False, True]
    mock_node_2 = mock.Mock()
    mock_node_2.is_active.side_effect = [False, True]

    test_cluster = cluster.Cluster(test_cluster_name, test_num_nodes, mock_docker_client, test_image)
    test_cluster._nodes = {
        test_cluster._generate_node_name(1): mock_node_1,
        test_cluster._generate_node_name(2): mock_node_2
    }

    # Run our test
    with pytest.raises(cluster.ClusterNotStartedInTimeException):
        test_cluster.wait_for_cluster_to_start_up(1)

    # Check the results
    assert 1 == mock_time.sleep.call_count
    assert 2 == mock_node_1.is_active.call_count
    assert 2 == mock_node_2.is_active.call_count

def test_WHEN_stop_THEN_as_expected():
    # Set up test
    test_cluster_name = "cluster-name"
    test_num_nodes = 2
    mock_docker_client = mock.Mock()
    test_image = "image"

    mock_node_1 = mock.Mock()
    mock_node_1.is_active.side_effect = [False, False, True]
    mock_node_2 = mock.Mock()
    mock_node_2.is_active.side_effect = [False, True]

    test_cluster = cluster.Cluster(test_cluster_name, test_num_nodes, mock_docker_client, test_image)
    test_cluster._nodes = {
        test_cluster._generate_node_name(1): mock_node_1,
        test_cluster._generate_node_name(2): mock_node_2
        }

    # Run our test
    test_cluster.stop()

    # Check the results
    assert 1 == mock_node_1.stop.call_count
    assert 1 == mock_node_2.stop.call_count

def test_WHEN_clean_up_THEN_as_expected():
    # Set up test
    test_cluster_name = "cluster-name"
    test_num_nodes = 2
    mock_docker_client = mock.Mock()
    test_image = "image"

    mock_network = mock.Mock()
    mock_volume_1 = mock.Mock()
    mock_volume_2 = mock.Mock()
    mock_node_1 = mock.Mock()
    mock_node_2 = mock.Mock()

    test_cluster = cluster.Cluster(test_cluster_name, test_num_nodes, mock_docker_client, test_image)
    test_cluster._networks = [mock_network]
    test_cluster._volumes = [mock_volume_1, mock_volume_2]
    test_cluster._nodes = {
        test_cluster._generate_node_name(1): mock_node_1,
        test_cluster._generate_node_name(2): mock_node_2
        }

    # Run our test
    test_cluster.clean_up()

    # Check the results
    assert 1 == mock_node_1.clean_up.call_count
    assert 1 == mock_node_2.clean_up.call_count

    expected_remove_networks = [mock.call(mock_network)]
    assert expected_remove_networks == test_cluster._docker_client.remove_network.call_args_list

    expected_remove_networks = [mock.call(mock_volume_1), mock.call(mock_volume_2)]
    assert expected_remove_networks == test_cluster._docker_client.remove_volume.call_args_list
    