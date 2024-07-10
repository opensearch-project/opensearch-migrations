import pathlib

import pytest

from console_link.environment import Environment
from console_link.models.backfill_base import Backfill
from console_link.models.cluster import Cluster
from console_link.models.metrics_source import MetricsSource

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
VALID_SERVICES_YAML = TEST_DATA_DIRECTORY / "services.yaml"


def create_file_in_tmp_path(tmp_path, file_name, content):
    file_path = tmp_path / file_name
    file_path.write_text(content)
    return file_path


def test_valid_services_yaml_to_environment_succeeds():
    env = Environment(VALID_SERVICES_YAML)
    assert env is not None
    assert env.source_cluster is not None
    assert env.target_cluster is not None
    print(env.source_cluster)
    print(type(env.source_cluster))
    assert isinstance(env.source_cluster, Cluster)
    assert isinstance(env.target_cluster, Cluster)
    assert env.backfill is not None
    assert isinstance(env.backfill, Backfill)
    assert env.metrics_source is not None
    assert isinstance(env.metrics_source, MetricsSource)


MINIMAL_YAML = """
target_cluster:
  endpoint: http://endpoint.com
  no_auth:
"""


def test_minimial_services_yaml_to_environment_works(tmp_path):
    minimal_yaml_path = create_file_in_tmp_path(tmp_path, "minimal.yaml", MINIMAL_YAML)
    env = Environment(minimal_yaml_path)
    assert env is not None
    assert env.source_cluster is None
    assert env.backfill is None
    assert env.metrics_source is None

    assert env.target_cluster is not None
    assert isinstance(env.target_cluster, Cluster)


INVALID_YAML = """
source_cluster:
  this_field_doesnt_exist: 0
backfill:
made_up_field:
"""


def test_invalid_services_yaml_to_environment_raises_error(tmp_path):
    invalid_yaml_path = create_file_in_tmp_path(tmp_path, "invalid.yaml", INVALID_YAML)
    with pytest.raises(ValueError):
        Environment(invalid_yaml_path)
