import pytest
import unittest.mock as mock

import cluster_migration_core.cluster_management.cluster as cluster
import cluster_migration_core.core.test_config_wrangling as tcw

TEST_CLUSTER_CONFIG = tcw.ClusterConfig({
    "engine_version": "ES_7_10_2",
    "image": "image",
    "node_count": 2,
    "additional_node_config": {}
})


def test_WHEN_create_Cluster_AND_not_enough_nodes_THEN_raises():
    # Set up test
    test_cluster_config = tcw.ClusterConfig({
        "engine_version": "ES_7_10_2",
        "image": "image",
        "node_count": 0,  # must be >= 1
        "additional_node_config": {}
    })
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    # Run our test
    with pytest.raises(ValueError):
        cluster.Cluster(test_cluster_name, test_cluster_config, mock_docker_client)


@mock.patch('cluster_migration_core.cluster_management.cluster.Node')
def test_WHEN_start_THEN_as_expected(mock_node_class):
    # Set up test
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    mock_network = mock.Mock()
    mock_volume_1 = mock.Mock()
    mock_volume_2 = mock.Mock()
    mock_docker_client.create_network.return_value = mock_network
    mock_docker_client.create_volume.side_effect = [mock_volume_1, mock_volume_2]

    mock_node_1 = mock.Mock()
    mock_node_1.rest_port = 9200
    mock_node_2 = mock.Mock()
    mock_node_2.rest_port = 9201
    mock_node_class.side_effect = [mock_node_1, mock_node_2]

    test_cluster = cluster.Cluster(test_cluster_name, TEST_CLUSTER_CONFIG, mock_docker_client)

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

    assert test_cluster._cluster_state == cluster.STATE_RUNNING


@mock.patch('cluster_migration_core.cluster_management.cluster.Node')
def test_WHEN_start_AND_already_running_THEN_no_op(mock_node_class):
    # Set up test
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    test_cluster = cluster.Cluster(test_cluster_name, TEST_CLUSTER_CONFIG, mock_docker_client)
    test_cluster._cluster_state = cluster.STATE_RUNNING

    # Run our test
    test_cluster.start()

    # Check the results
    assert 0 == mock_node_class.call_count


def test_WHEN_start_AND_stopped_THEN_raises():
    # Set up test
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    test_cluster = cluster.Cluster(test_cluster_name, TEST_CLUSTER_CONFIG, mock_docker_client)
    test_cluster._cluster_state = cluster.STATE_STOPPED

    # Run our test
    with pytest.raises(cluster.ClusterRestartNotAllowedException):
        test_cluster.start()


def test_WHEN_start_AND_cleaned_THEN_raises():
    # Set up test
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    test_cluster = cluster.Cluster(test_cluster_name, TEST_CLUSTER_CONFIG, mock_docker_client)
    test_cluster._cluster_state = cluster.STATE_CLEANED

    # Run our test
    with pytest.raises(cluster.ClusterRestartNotAllowedException):
        test_cluster.start()


@mock.patch('cluster_migration_core.cluster_management.cluster.time')
def test_WHEN_wait_for_cluster_to_start_up_THEN_as_expected(mock_time):
    # Set up test
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    mock_node_1 = mock.Mock()
    mock_node_1.is_active.side_effect = [False, False, True]
    mock_node_2 = mock.Mock()
    mock_node_2.is_active.side_effect = [False, True]

    test_cluster = cluster.Cluster(test_cluster_name, TEST_CLUSTER_CONFIG, mock_docker_client)
    test_cluster._cluster_state = cluster.STATE_RUNNING
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


@mock.patch('cluster_migration_core.cluster_management.cluster.time')
def test_WHEN_wait_for_cluster_to_start_up_AND_max_wait_exceeded_THEN_raises(mock_time):
    # Set up test
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    mock_node_1 = mock.Mock()
    mock_node_1.is_active.side_effect = [False, False, True]
    mock_node_2 = mock.Mock()
    mock_node_2.is_active.side_effect = [False, True]

    test_cluster = cluster.Cluster(test_cluster_name, TEST_CLUSTER_CONFIG, mock_docker_client)
    test_cluster._cluster_state = cluster.STATE_RUNNING
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


def test_WHEN_wait_for_cluster_to_start_up_AND_max_wait_exceeded_again_THEN_raises():
    # Set up test
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    test_cluster = cluster.Cluster(test_cluster_name, TEST_CLUSTER_CONFIG, mock_docker_client)

    # Run our test
    with pytest.raises(cluster.ClusterNotRunningException):
        test_cluster.wait_for_cluster_to_start_up(1)


def test_WHEN_stop_THEN_as_expected():
    # Set up test
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    mock_node_1 = mock.Mock()
    mock_node_1.is_active.side_effect = [False, False, True]
    mock_node_2 = mock.Mock()
    mock_node_2.is_active.side_effect = [False, True]

    test_cluster = cluster.Cluster(test_cluster_name, TEST_CLUSTER_CONFIG, mock_docker_client)
    test_cluster._cluster_state = cluster.STATE_RUNNING
    test_cluster._nodes = {
        test_cluster._generate_node_name(1): mock_node_1,
        test_cluster._generate_node_name(2): mock_node_2
    }

    # Run our test
    test_cluster.stop()

    # Check the results
    assert 1 == mock_node_1.stop.call_count
    assert 1 == mock_node_2.stop.call_count


def test_WHEN_stop_AND_not_running_THEN_no_op():
    # Set up test
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    mock_node_1 = mock.Mock()
    mock_node_2 = mock.Mock()

    test_cluster = cluster.Cluster(test_cluster_name, TEST_CLUSTER_CONFIG, mock_docker_client)
    test_cluster._nodes = {
        test_cluster._generate_node_name(1): mock_node_1,
        test_cluster._generate_node_name(2): mock_node_2
    }

    # Run our test
    test_cluster.stop()

    # Check the results
    assert not mock_node_1.stop.called
    assert not mock_node_2.stop.called


def test_WHEN_clean_up_THEN_as_expected():
    # Set up test
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    mock_network = mock.Mock()
    mock_volume_1 = mock.Mock()
    mock_volume_2 = mock.Mock()
    mock_node_1 = mock.Mock()
    mock_node_2 = mock.Mock()

    test_cluster = cluster.Cluster(test_cluster_name, TEST_CLUSTER_CONFIG, mock_docker_client)
    test_cluster._networks = [mock_network]
    test_cluster._volumes = [mock_volume_1, mock_volume_2]
    test_cluster._nodes = {
        test_cluster._generate_node_name(1): mock_node_1,
        test_cluster._generate_node_name(2): mock_node_2
    }
    test_cluster._cluster_state = cluster.STATE_STOPPED

    # Run our test
    test_cluster.clean_up()

    # Check the results
    assert 1 == mock_node_1.clean_up.call_count
    assert 1 == mock_node_2.clean_up.call_count

    expected_remove_networks = [mock.call(mock_network)]
    assert expected_remove_networks == test_cluster._docker_client.remove_network.call_args_list

    expected_remove_volumes = [mock.call(mock_volume_1), mock.call(mock_volume_2)]
    assert expected_remove_volumes == test_cluster._docker_client.remove_volume.call_args_list

    assert 0 == len(test_cluster._networks)
    assert 2 == len(test_cluster._nodes)
    assert 0 == len(test_cluster._volumes)

    assert cluster.STATE_CLEANED == test_cluster._cluster_state


def test_WHEN_clean_up_AND_not_stopped_THEN_raises():
    # Set up test
    test_cluster_name = "cluster-name"
    mock_docker_client = mock.Mock()

    test_cluster = cluster.Cluster(test_cluster_name, TEST_CLUSTER_CONFIG, mock_docker_client)

    # Run our test
    with pytest.raises(cluster.ClusterNotStoppedException):
        test_cluster.clean_up()
