import json
import pathlib

import botocore.session
import pytest
from botocore.stub import Stubber

from console_link.models.ecs_service import ECSService
from console_link.models.utils import AWSAPIError, DeploymentStatus

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
AWS_REGION = "us-east-1"


@pytest.fixture
def ecs_stubber():
    cw_session = botocore.session.get_session().create_client("ecs", region_name=AWS_REGION)
    stubber = Stubber(cw_session)
    return stubber


@pytest.fixture
def ecs_service():
    return ECSService("migration-aws-integ-ecs-cluster",
                      "migration-aws-integ-reindex-from-snapshot",
                      AWS_REGION)


def test_ecs_created_with_and_without_region():
    with_region = ECSService("cluster_name", "service_name", AWS_REGION)
    assert isinstance(with_region, ECSService)
    assert with_region.client is not None

    without_region = ECSService("cluster_name", "service_name")
    assert isinstance(without_region, ECSService)
    assert without_region.client is not None


def test_ecs_update_service_succesful(ecs_stubber, ecs_service):
    with open(TEST_DATA_DIRECTORY / "ecs_update_service_response.json") as f:
        ecs_stubber.add_response("update_service", json.load(f))
    ecs_stubber.activate()

    ecs_service.client = ecs_stubber.client
    result = ecs_service.set_desired_count(1)
    assert result.success is True
    assert result.value is not None


def test_ecs_update_service_unsuccessful(ecs_stubber, ecs_service):
    with open(TEST_DATA_DIRECTORY / "ecs_update_service_response_failed.json") as f:
        ecs_stubber.add_response("update_service", json.load(f))
    ecs_stubber.activate()

    ecs_service.client = ecs_stubber.client
    result = ecs_service.set_desired_count(1)
    assert result.success is False
    assert isinstance(result.value, AWSAPIError)


def test_ecs_get_instance_statues(ecs_stubber, ecs_service):
    with open(TEST_DATA_DIRECTORY / "ecs_describe_services_5d_2p_1r.json") as f:
        data = json.load(f)
        ecs_stubber.add_response("describe_services", service_response=data)
    ecs_stubber.activate()

    expected = DeploymentStatus(desired=5, pending=2, running=1)

    ecs_service.client = ecs_stubber.client
    result = ecs_service.get_instance_statuses()
    assert str(result) == str(expected)
