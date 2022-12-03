import pytest
import unittest.mock as mock
from docker.types import Ulimit
from docker.errors import ImageNotFound, NotFound
from upgrade_testing_framework.core.docker_framework_client import DockerFrameworkClient, RemoveRunningContainerException, ExistingContainerException


class EndTestExpectedException(Exception):
    pass


@mock.patch('upgrade_testing_framework.core.docker_framework_client.docker.client.DockerClient')
def test_WHEN_create_container_called_THEN_executes_normally(mock_sdk_client):
    # Test values
    mock_sdk_client.containers.get.side_effect = NotFound(message="Not found")
    mock_sdk_client.images.get.return_value = None
    mock_sdk_client.containers.run.side_effect = EndTestExpectedException()
    docker_client = DockerFrameworkClient(docker_client=mock_sdk_client)

    # Run our test
    with pytest.raises(EndTestExpectedException):
        docker_client.create_container('opensearchproject/sample:1.0.0', 'test-node1', None, {'9200': '9200'}, {'bind': '/test'},
                                       [Ulimit(name='memlock', soft=-1, hard=-1)], {'cluster.name': 'test-cluster'})

    # Check our results
    assert mock_sdk_client.containers.get.called
    assert mock_sdk_client.images.get.called
    assert mock_sdk_client.containers.run.called
    expected_calls = [mock.call('opensearchproject/sample:1.0.0', name='test-node1', network=None, ports={'9200': '9200'},
                                volumes={'bind': '/test'}, ulimits=[{'Name': 'memlock', 'Soft': -1, 'Hard': -1}], detach=True,
                                environment={'cluster.name': 'test-cluster'})]
    assert expected_calls == mock_sdk_client.containers.run.call_args_list


@mock.patch('upgrade_testing_framework.core.docker_framework_client.docker.client.DockerClient')
def test_WHEN_create_container_called_AND_container_name_exists_THEN_throws_error(mock_sdk_client):
    # Test values
    mock_sdk_client.containers.get.return_value = None
    docker_client = DockerFrameworkClient(docker_client=mock_sdk_client)

    # Run our test
    with pytest.raises(ExistingContainerException):
        docker_client.create_container('opensearchproject/sample:1.0.0', 'test-node1', None, None, None,None, None)

    # Check our results
    assert mock_sdk_client.containers.get.called


@mock.patch('upgrade_testing_framework.core.docker_framework_client.docker.client.DockerClient')
def test_WHEN_create_container_called_AND_image_does_not_exist_locally_THEN_fetches_image(mock_sdk_client):
    # Test values
    mock_sdk_client.containers.get.side_effect = NotFound(message="Not found")
    mock_sdk_client.images.get.side_effect = ImageNotFound(message="Image not found")
    mock_sdk_client.images.pull.side_effect = EndTestExpectedException()
    docker_client = DockerFrameworkClient(docker_client=mock_sdk_client)

    # Run our test
    with pytest.raises(EndTestExpectedException):
        docker_client.create_container('opensearchproject/sample:1.0.0', 'test-node1', None, None, None,None, None)

    # Check our results
    assert mock_sdk_client.images.get.called
    assert mock_sdk_client.images.pull.called


@mock.patch('upgrade_testing_framework.core.docker_framework_client.docker.models.containers.Container')
def test_WHEN_remove_container_called_THEN_executes_normally(mock_container):
    # Test values
    docker_client = DockerFrameworkClient()

    # Run our test
    docker_client.remove_container(mock_container)

    # Check our results
    expected_calls = [mock.call()]
    assert expected_calls == mock_container.remove.call_args_list


@mock.patch('upgrade_testing_framework.core.docker_framework_client.docker.models.containers.Container')
def test_WHEN_remove_container_called_AND_container_is_running_THEN_throws_error(mock_container):
    # Test values
    mock_container.attrs = {'State': 'running'}
    docker_client = DockerFrameworkClient()

    # Run our test/Check result
    with pytest.raises(RemoveRunningContainerException):
        docker_client.remove_container(mock_container)


def test_WHEN_remove_container_called_AND_container_is_wrong_type_THEN_throws_error():
    # Test values
    container_none = None
    docker_client = DockerFrameworkClient()

    # Run our test/Check result
    with pytest.raises(TypeError):
        docker_client.remove_container(container_none)
