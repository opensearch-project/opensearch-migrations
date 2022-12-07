import os
import pytest
import unittest.mock as mock

from docker.errors import DockerException, ImageNotFound

import upgrade_testing_framework.cluster_management.docker_framework_client as dfc



# from docker.types import Ulimit
# from docker.errors import DockerException, ImageNotFound, NotFound

@mock.patch.dict(os.environ, {"PATH": ""})
def test_WHEN_create_docker_client_AND_docker_not_in_path_THEN_raises():
    # Run our test
    with pytest.raises(dfc.DockerNotInPathException):
        dfc.DockerFrameworkClient()

@mock.patch('upgrade_testing_framework.cluster_management.docker_framework_client.docker.client')
def test_WHEN_create_docker_client_AND_docker_not_running_THEN_raises(mock_dock_client_module):
    # Set up our test
    mock_dock_client_module.from_env.side_effect = DockerException()

    # Run our test
    with pytest.raises(dfc.DockerNotResponsiveException):
        dfc.DockerFrameworkClient()

def test_WHEN_ensure_image_available_AND_is_local_THEN_returns():
    # Set up our test
    mock_inner_client = mock.Mock() # no exception thrown when we invoke docker_client.images.get()

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    test_client.ensure_image_available("test-image")

    # Check our results
    expected_get_calls = [mock.call("test-image")]
    assert expected_get_calls == mock_inner_client.images.get.call_args_list

def test_WHEN_ensure_image_available_AND_is_not_local_THEN_pulls():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_inner_client.images.get.side_effect = ImageNotFound("Not found")

    # Run our test
    test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
    test_client.ensure_image_available("test-image")

    # Check our results
    expected_pull_calls = [mock.call("test-image")]
    assert expected_pull_calls == mock_inner_client.images.pull.call_args_list

def test_WHEN_ensure_image_available_AND_is_not_available_THEN_raises():
    # Set up our test
    mock_inner_client = mock.Mock()
    mock_inner_client.images.get.side_effect = ImageNotFound("Not found")
    mock_inner_client.images.pull.side_effect = ImageNotFound("Not found")

    # Run our test
    with pytest.raises(dfc.DockerImageUnavailableException):
        test_client = dfc.DockerFrameworkClient(docker_client=mock_inner_client)
        test_client.ensure_image_available("test-image")

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

# class EndTestExpectedException(Exception):
#     pass

# @mock.patch('upgrade_testing_framework.core.docker_framework_client.docker.client.DockerClient')
# def test_WHEN_create_container_called_THEN_executes_normally(mock_sdk_client):
#     # Test values
#     mock_sdk_client.containers.get.side_effect = NotFound(message="Not found")
#     mock_sdk_client.images.get.return_value = None
#     mock_sdk_client.containers.run.side_effect = EndTestExpectedException()
#     docker_client = DockerFrameworkClient(docker_client=mock_sdk_client)

#     # Run our test
#     with pytest.raises(EndTestExpectedException):
#         docker_client.create_container('opensearchproject/sample:1.0.0', 'test-node1', None, {'9200': '9200'}, {'bind': '/test'},
#                                        [Ulimit(name='memlock', soft=-1, hard=-1)], {'cluster.name': 'test-cluster'})

#     # Check our results
#     assert mock_sdk_client.containers.get.called
#     assert mock_sdk_client.images.get.called
#     assert mock_sdk_client.containers.run.called
#     expected_calls = [mock.call('opensearchproject/sample:1.0.0', name='test-node1', network=None, ports={'9200': '9200'},
#                                 volumes={'bind': '/test'}, ulimits=[{'Name': 'memlock', 'Soft': -1, 'Hard': -1}], detach=True,
#                                 environment={'cluster.name': 'test-cluster'})]
#     assert expected_calls == mock_sdk_client.containers.run.call_args_list


# @mock.patch('upgrade_testing_framework.core.docker_framework_client.docker.client.DockerClient')
# def test_WHEN_create_container_called_AND_container_name_exists_THEN_throws_error(mock_sdk_client):
#     # Test values
#     mock_sdk_client.containers.get.return_value = None
#     docker_client = DockerFrameworkClient(docker_client=mock_sdk_client)

#     # Run our test
#     with pytest.raises(ExistingContainerException):
#         docker_client.create_container('opensearchproject/sample:1.0.0', 'test-node1', None, None, None,None, None)

#     # Check our results
#     assert mock_sdk_client.containers.get.called


# @mock.patch('upgrade_testing_framework.core.docker_framework_client.docker.client.DockerClient')
# def test_WHEN_create_container_called_AND_image_does_not_exist_locally_THEN_fetches_image(mock_sdk_client):
#     # Test values
#     mock_sdk_client.containers.get.side_effect = NotFound(message="Not found")
#     mock_sdk_client.images.get.side_effect = ImageNotFound(message="Image not found")
#     mock_sdk_client.images.pull.side_effect = EndTestExpectedException()
#     docker_client = DockerFrameworkClient(docker_client=mock_sdk_client)

#     # Run our test
#     with pytest.raises(EndTestExpectedException):
#         docker_client.create_container('opensearchproject/sample:1.0.0', 'test-node1', None, None, None,None, None)

#     # Check our results
#     assert mock_sdk_client.images.get.called
#     assert mock_sdk_client.images.pull.called


# @mock.patch('upgrade_testing_framework.core.docker_framework_client.docker.client.DockerClient')
# @mock.patch('upgrade_testing_framework.core.docker_framework_client.docker.models.containers.Container')
# def test_WHEN_remove_container_called_THEN_executes_normally(mock_container, mock_sdk_client):
#     # Test values
#     docker_client = DockerFrameworkClient(docker_client=mock_sdk_client)

#     # Run our test
#     docker_client.remove_container(mock_container)

#     # Check our results
#     expected_calls = [mock.call()]
#     assert expected_calls == mock_container.remove.call_args_list


# @mock.patch('upgrade_testing_framework.core.docker_framework_client.docker.models.containers.Container')
# def test_WHEN_remove_container_called_AND_container_is_running_THEN_throws_error(mock_container):
#     # Test values
#     mock_container.attrs = {'State': 'running'}
#     docker_client = DockerFrameworkClient()

#     # Run our test/Check result
#     with pytest.raises(RemoveRunningContainerException):
#         docker_client.remove_container(mock_container)


# def test_WHEN_remove_container_called_AND_container_is_wrong_type_THEN_throws_error():
#     # Test values
#     container_none = None
#     docker_client = DockerFrameworkClient()

#     # Run our test/Check result
#     with pytest.raises(TypeError):
#         docker_client.remove_container(container_none)
