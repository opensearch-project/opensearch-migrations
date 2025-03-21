import json
from kubernetes import config
import pathlib

import pytest
import yaml

import console_link.middleware.replay as replay_
from console_link.models.ecs_service import ECSService
from console_link.models.factories import UnsupportedReplayerError, get_replayer
from console_link.models.kubectl_runner import KubectlRunner
from console_link.models.replayer_base import Replayer
from console_link.models.replayer_ecs import ECSReplayer
from console_link.models.replayer_k8s import K8sReplayer
from console_link.models.replayer_docker import DockerReplayer

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
AWS_REGION = "us-east-1"


@pytest.fixture(autouse=True)
def mock_kube_config(monkeypatch):
    # Prevent actual config loading
    monkeypatch.setattr(config, "load_incluster_config", lambda: None)
    monkeypatch.setattr(config, "load_kube_config", lambda: None)


@pytest.fixture
def docker_replayer():
    config = {
        "docker": {}
    }
    return get_replayer(config)


@pytest.fixture
def ecs_replayer():
    config = {
        "ecs": {
            "cluster_name": "migration-aws-integ-ecs-cluster",
            "service_name": "migration-aws-integ-traffic-replayer-default"
        },
        "scale": 3
    }
    return get_replayer(config)


@pytest.fixture
def k8s_replayer():
    config = {
        "k8s": {
            "namespace": "ma",
            "deployment_name": "ma-replayer"
        },
        "scale": 3
    }
    return get_replayer(config)


def _mock_ecs_set_desired_count(mocker):
    return mocker.patch.object(ECSService, 'set_desired_count', autospec=True)


def _mock_ecs_validate_desired_count(mock, replayer, expected_count):
    mock.assert_called_once_with(replayer.ecs_client, expected_count)


def _mock_k8s_set_desired_count(mocker):
    return mocker.patch.object(KubectlRunner, 'perform_scale_command', autospec=True)


def _mock_k8s_validate_desired_count(mock, replayer, expected_count):
    mock.assert_called_once_with(replayer.kubectl_runner, expected_count)


def test_get_replayer_valid_docker(docker_replayer):
    assert isinstance(docker_replayer, DockerReplayer)
    assert isinstance(docker_replayer, Replayer)


def test_get_replayer_valid_ecs(ecs_replayer):
    assert isinstance(ecs_replayer, ECSReplayer)
    assert isinstance(ecs_replayer, Replayer)


def test_get_replayer_valid_k8s(k8s_replayer):
    assert isinstance(k8s_replayer, K8sReplayer)
    assert isinstance(k8s_replayer, Replayer)


def test_cant_instantiate_with_multiple_types():
    config = {
        "ecs": {
            "cluster_name": "migration-aws-integ-ecs-cluster",
            "service_name": "migration-aws-integ-traffic-replayer-default"
        },
        "docker": {
        },
        "scale": 3
    }
    with pytest.raises(ValueError) as excinfo:
        get_replayer(config)
    assert "Invalid config file for replay" in str(excinfo.value.args[0])
    assert "More than one value is present" in str(excinfo.value.args[1]['replay'][0])


def test_nonexistent_replayer_type():
    config = {
        "new_replayer_type": {
            "setting": "value"
        }
    }
    with pytest.raises(UnsupportedReplayerError) as exc_info:
        get_replayer(config)
    assert 'new_replayer_type' in exc_info.value.args


@pytest.mark.parametrize(
    "replayer_fixture, mock_desired_count, validate_desired_count",
    [
        ('ecs_replayer', _mock_ecs_set_desired_count, _mock_ecs_validate_desired_count),
        ('k8s_replayer', _mock_k8s_set_desired_count, _mock_k8s_validate_desired_count),
    ]
)
def test_replayer_start_sets_desired_count(request,
                                           replayer_fixture,
                                           mock_desired_count,
                                           validate_desired_count,
                                           mocker):
    replayer = request.getfixturevalue(replayer_fixture)
    mock = mock_desired_count(mocker)
    replayer.start()
    validate_desired_count(mock, replayer, replayer.config["scale"])


@pytest.mark.parametrize(
    "replayer_fixture, mock_desired_count, validate_desired_count",
    [
        ('ecs_replayer', _mock_ecs_set_desired_count, _mock_ecs_validate_desired_count),
        ('k8s_replayer', _mock_k8s_set_desired_count, _mock_k8s_validate_desired_count),
    ]
)
def test_replayer_stop_sets_desired_count(request,
                                          replayer_fixture,
                                          mock_desired_count,
                                          validate_desired_count,
                                          mocker):
    replayer = request.getfixturevalue(replayer_fixture)
    mock = mock_desired_count(mocker)
    replayer.stop()
    validate_desired_count(mock, replayer, 0)


@pytest.mark.parametrize(
    "replayer_fixture, mock_desired_count, validate_desired_count",
    [
        ('ecs_replayer', _mock_ecs_set_desired_count, _mock_ecs_validate_desired_count),
        ('k8s_replayer', _mock_k8s_set_desired_count, _mock_k8s_validate_desired_count),
    ]
)
def test_replayer_scale_sets_desired_count(request,
                                           replayer_fixture,
                                           mock_desired_count,
                                           validate_desired_count,
                                           mocker):
    replayer = request.getfixturevalue(replayer_fixture)
    mock = mock_desired_count(mocker)
    replayer.scale(5)
    validate_desired_count(mock, replayer, 5)


@pytest.mark.parametrize("replayer_fixture", ['ecs_replayer', 'k8s_replayer'])
def test_replayer_describe_no_json(request, replayer_fixture):
    replayer = request.getfixturevalue(replayer_fixture)
    success, output = replay_.describe(replayer, as_json=False)
    assert success
    assert output == yaml.safe_dump(replayer.config)


@pytest.mark.parametrize("replayer_fixture", ['ecs_replayer', 'k8s_replayer'])
def test_replayer_describe_as_json(request, replayer_fixture):
    replayer = request.getfixturevalue(replayer_fixture)
    success, output = replay_.describe(replayer, as_json=True)
    assert success
    assert json.loads(output) == replayer.config
