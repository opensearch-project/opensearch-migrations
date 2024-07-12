import json
import pathlib

import pytest
import yaml

import console_link.middleware.replay as replay_
from console_link.models.ecs_service import ECSService
from console_link.models.factories import get_replayer
from console_link.models.replayer_base import Replayer
from console_link.models.replayer_ecs import ECSReplayer

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
AWS_REGION = "us-east-1"


def test_get_replayer_valid_ecs():
    ecs_config = {
        "ecs": {
            "cluster_name": "migration-aws-integ-ecs-cluster",
            "service_name": "migration-aws-integ-traffic-replayer-default"
        },
        "scale": 3
    }
    replayer = get_replayer(ecs_config)
    assert isinstance(replayer, ECSReplayer)
    assert isinstance(replayer, Replayer)


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


def test_replayer_start_sets_ecs_desired_count(mocker):
    config = {
        "ecs": {
            "cluster_name": "migration-aws-integ-ecs-cluster",
            "service_name": "migration-aws-integ-traffic-replayer-default"
        },
        "scale": 3
    }
    replayer = get_replayer(config)
    mock = mocker.patch.object(ECSService, 'set_desired_count', autospec=True)
    replayer.start()

    assert isinstance(replayer, ECSReplayer)
    mock.assert_called_once_with(replayer.ecs_client, config["scale"])


def test_replayer_stop_sets_ecs_desired_count(mocker):
    config = {
        "ecs": {
            "cluster_name": "migration-aws-integ-ecs-cluster",
            "service_name": "migration-aws-integ-traffic-replayer-default"
        },
        "scale": 3
    }
    replayer = get_replayer(config)
    mock = mocker.patch.object(ECSService, 'set_desired_count', autospec=True)
    replayer.stop()

    assert isinstance(replayer, ECSReplayer)
    mock.assert_called_once_with(replayer.ecs_client, 0)


def test_replayer_scale_sets_ecs_desired_count(mocker):
    config = {
        "ecs": {
            "cluster_name": "migration-aws-integ-ecs-cluster",
            "service_name": "migration-aws-integ-traffic-replayer-default"
        }
    }
    replayer = get_replayer(config)
    mock = mocker.patch.object(ECSService, 'set_desired_count', autospec=True)
    replayer.scale(5)

    assert isinstance(replayer, ECSReplayer)
    mock.assert_called_once_with(replayer.ecs_client, 5)


def test_replayer_describe_no_json():
    config = {
        "ecs": {
            "cluster_name": "migration-aws-integ-ecs-cluster",
            "service_name": "migration-aws-integ-traffic-replayer-default"
        },
        "scale": 3
    }
    replayer = get_replayer(config)
    success, output = replay_.describe(replayer, as_json=False)
    assert success
    assert output == yaml.safe_dump(config)


def test_replayer_describe_as_json():
    config = {
        "ecs": {
            "cluster_name": "migration-aws-integ-ecs-cluster",
            "service_name": "migration-aws-integ-traffic-replayer-default"
        },
        "scale": 3
    }
    replayer = get_replayer(config)
    success, output = replay_.describe(replayer, as_json=True)
    assert success
    assert json.loads(output) == config
