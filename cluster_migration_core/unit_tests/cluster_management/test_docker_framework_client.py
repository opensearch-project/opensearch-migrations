import os
import pytest
import unittest.mock as mock

from docker.errors import DockerException, ImageNotFound

import cluster_migration_core.cluster_management.docker_framework_client as dfc


@mock.patch.dict(os.environ, {"PATH": ""})
def test_WHEN_create_docker_client_AND_docker_not_in_path_THEN_raises():
    # Run our test
    with pytest.raises(dfc.DockerNotInPathException):
        dfc.DockerFrameworkClient()


@mock.patch('cluster_migration_core.cluster_management.docker_framework_client.docker.client')
def test_WHEN_create_docker_client_AND_docker_not_running_THEN_raises(mock_dock_client_module):
    # Set up our test
    mock_dock_client_module.from_env.side_effect = DockerException()

    # Run our test
    with pytest.raises(dfc.DockerNotResponsiveException):
        dfc.DockerFrameworkClient()


def test_WHEN_is_image_available_locally_AND_is_available_THEN_true():
    # Set up our test
    mock_inner_client = mock.Mock()  # no exception thrown when we invoke docker_client.images.get()

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    actual_value = test_client.is_image_available_locally("test-image")

    # Check our results
    expected_value = True
    assert expected_value == actual_value

    expected_get_calls = [mock.call("test-image")]
    assert expected_get_calls == mock_inner_client.images.get.call_args_list


def test_WHEN_is_image_available_locally_AND_not_available_THEN_false():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_inner_client.images.get.side_effect = ImageNotFound("Not found")

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    actual_value = test_client.is_image_available_locally("test-image")

    # Check our results
    expected_value = False
    assert expected_value == actual_value


def test_WHEN_pull_image_AND_is_available_THEN_pulls():
    # Set up our test
    mock_inner_client = mock.Mock()  # no exception thrown when we invoke docker_client.images.pull()

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    test_client.pull_image("test-image")

    # Check our results
    expected_pull_calls = [mock.call("test-image")]
    assert expected_pull_calls == mock_inner_client.images.pull.call_args_list


def test_WHEN_pull_image_AND_not_available_THEN_raises():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_inner_client.images.pull.side_effect = ImageNotFound("Not found")

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    with pytest.raises(dfc.DockerImageUnavailableException):
        test_client.pull_image("test-image")


def test_WHEN_create_network_THEN_returns_it():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_network = mock.Mock()
    mock_inner_client.networks.create.return_value = mock_network

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    test_network = test_client.create_network("network-name")

    # Check our results
    assert mock_network == test_network
    expected_create_calls = [mock.call("network-name", driver="bridge")]
    assert expected_create_calls == mock_inner_client.networks.create.call_args_list


def test_WHEN_remove_network_THEN_removes_it():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_network = mock.Mock()

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    test_client.remove_network(mock_network)

    # Check our results
    assert mock_network.remove.called


def test_WHEN_create_volume_THEN_returns_it():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_volume = mock.Mock()
    mock_inner_client.volumes.create.return_value = mock_volume

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    test_volume = test_client.create_volume("volume-name")

    # Check our results
    assert mock_volume == test_volume
    expected_create_calls = [mock.call("volume-name")]
    assert expected_create_calls == mock_inner_client.volumes.create.call_args_list


def test_WHEN_remove_volume_THEN_removes_it():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_volume = mock.Mock()

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    test_client.remove_volume(mock_volume)

    # Check our results
    assert mock_volume.remove.called


def test_WHEN_create_container_called_THEN_executes_normally():
    # Set up our test
    mock_inner_client = mock.Mock()
    test_image = "test-image"
    test_container_name = "test-container"
    mock_network = mock.Mock()
    mock_network.name = "network1"
    test_ports = [dfc.PortMapping(1, 1), dfc.PortMapping(2, 3)]
    mock_volume = mock.Mock()
    mock_volume.attrs = {"Name": "volume1"}
    mock_docker_volume = dfc.DockerVolume("/mount/point", mock_volume)
    mock_ulimit = mock.Mock()
    test_env_vars = {"key": "value"}

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    test_client.create_container(
        test_image,
        test_container_name,
        mock_network,
        test_ports,
        [mock_docker_volume],
        [mock_ulimit],
        test_env_vars
    )

    # Check our results
    expected_calls = [
        mock.call(
            test_image,
            name=test_container_name,
            network=mock_network.name,
            ports={str(pair.container_port): str(pair.host_port) for pair in test_ports},
            volumes={mock_volume.attrs["Name"]: {"bind": mock_docker_volume.mount_point, "mode": "rw"}},
            ulimits=[mock_ulimit],
            detach=True,
            environment=test_env_vars
        )
    ]
    assert expected_calls == mock_inner_client.containers.run.call_args_list


def test_WHEN_stop_container_THEN_stops_it():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_container = mock.Mock()

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    test_client.stop_container(mock_container)

    # Check our results
    assert mock_container.stop.called


def test_WHEN_remove_container_THEN_removes_it():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_container = mock.Mock()

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    test_client.remove_container(mock_container)

    # Check our results
    assert mock_container.remove.called


def test_WHEN_run_command_THEN_runs_command():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_container = mock.Mock()

    test_return_value = (0, "line1\nline2")
    mock_container.exec_run.return_value = test_return_value

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    actual_value = test_client.run_command(mock_container, "test")

    # Check our results
    expected_args = [mock.call(
        "test"
    )]
    assert expected_args == mock_container.exec_run.call_args_list
    assert test_return_value == actual_value


def test_WHEN_set_ownership_of_directory_THEN_runs_command():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_container = mock.Mock()
    test_dir = "/my/dir"
    test_owner = "test_user"

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    test_client.set_ownership_of_directory(mock_container, test_owner, test_dir)

    # Check our results
    expected_args = [mock.call(
        f"chown -R {test_owner} {test_dir}"
    )]
    assert expected_args == mock_container.exec_run.call_args_list
