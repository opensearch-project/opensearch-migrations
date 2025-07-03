import json
import pathlib
import os
import time
import pytest
import requests_mock
from click.testing import CliRunner
from subprocess import CompletedProcess

import console_link.middleware as middleware
from console_link.cli import cli
from console_link.environment import Environment
from console_link.models.backfill_rfs import ECSRFSBackfill, RfsWorkersInProgress, WorkingIndexDoesntExist
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.command_result import CommandResult
from console_link.models.ecs_service import ECSService
from console_link.models.kafka import StandardKafka
from console_link.models.metrics_source import Component
from console_link.models.replayer_ecs import ECSReplayer
from console_link.models.utils import DeploymentStatus

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
VALID_SERVICES_YAML = TEST_DATA_DIRECTORY / "services.yaml"


@pytest.fixture
def runner():
    """A CliRunner for the cli function"""
    runner = CliRunner()
    return runner


@pytest.fixture
def env():
    """A valid Environment for the given VALID_SERVICES_YAML file"""
    return Environment(VALID_SERVICES_YAML)


@pytest.fixture(autouse=True)
def set_fake_aws_credentials():
    # These are example credentials from
    # https://docs.aws.amazon.com/IAM/latest/UserGuide/security-creds.html#sec-access-keys-and-secret-access-keys
    # They allow the boto client to be created for any AWS services, but functions must be intercepted
    # before any real calls are made.
    os.environ['AWS_ACCESS_KEY_ID'] = 'AKIAIOSFODNN7EXAMPLE'
    os.environ['AWS_SECRET_ACCESS_KEY'] = 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY'


@pytest.fixture
def source_cluster_only_yaml_path(tmp_path):
    source_cluster_only_path = tmp_path / "source_cluster_only.yaml"
    source_cluster_only_yaml = """
source_cluster:
  endpoint: "https://elasticsearch:9200"
  allow_insecure: true
  basic_auth:
    username: "admin"
    password: "admin"
"""
    with open(source_cluster_only_path, 'w') as f:
        f.write(source_cluster_only_yaml)
    return source_cluster_only_path


@pytest.fixture
def target_cluster_only_yaml_path(tmp_path):
    target_cluster_only_path = tmp_path / "target_cluster_only.yaml"
    target_cluster_only_yaml = """
target_cluster:
  endpoint: "https://opensearchtarget:9200"
  allow_insecure: true
  basic_auth:
    username: "admin"
    password: "myStrongPassword123!"
"""
    with open(target_cluster_only_path, 'w') as f:
        f.write(target_cluster_only_yaml)
    return target_cluster_only_path

# Tests around the general CLI functionality


def test_cli_without_valid_services_file_raises_error(runner):
    result = runner.invoke(cli, ['--config-file', '~/non-existent/file/services.yaml', 'clusters', 'cat-indices'])
    assert result.exit_code == 1
    assert "No such file or directory" in result.output or "No such file or directory" in result.stderr
    assert isinstance(result.exception, SystemExit)


def test_cli_with_valid_services_file_does_not_raise_error(runner):
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'backfill', 'describe'],
                           catch_exceptions=True)
    assert result.exit_code == 0


def test_cli_with_no_clusters_in_services_raises_error(runner, tmp_path):
    no_cluster_services = """
metrics_source:
  prometheus:
    endpoint: "http://prometheus:9090"
backfill:
  reindex_from_snapshot:
    docker:
snapshot:
  snapshot_name: "test_snapshot"
  fs:
    repo_path: "/snapshot/test-console"
  otel_endpoint: "http://otel-collector:4317"
  """
    yaml_path = tmp_path / "services.yaml"
    with open(yaml_path, 'w') as f:
        f.write(no_cluster_services)

    result = runner.invoke(cli, ['--config-file', str(yaml_path), 'clusters', 'connection-check'],
                           catch_exceptions=True)
    assert result.exit_code == 1
    assert isinstance(result.exception, SystemExit)


def test_cli_snapshot_when_source_cluster_not_defined_raises_error(runner, tmp_path):
    no_source_cluster_with_snapshot = """
target_cluster:
  endpoint: "https://opensearchtarget:9200"
  allow_insecure: true
  basic_auth:
    username: "admin"
    password: "myStrongPassword123!"
snapshot:
  snapshot_name: "test_snapshot"
  fs:
    repo_path: "/snapshot/test-console"
  otel_endpoint: "http://otel-collector:4317"
  """
    yaml_path = tmp_path / "services.yaml"
    with open(yaml_path, 'w') as f:
        f.write(no_source_cluster_with_snapshot)

    result = runner.invoke(cli, ['--config-file', str(yaml_path), 'snapshot', 'create'],
                           catch_exceptions=True)
    assert result.exit_code == 2
    assert "Snapshot commands require a source cluster to be defined" in result.output

# The following tests are mostly smoke-tests with a goal of covering every CLI command and option.
# They generally mock functions either at the logic or the model layer, though occasionally going all the way to
# an external endpoint call.
# Standardizing these in the future would be great, but the priority right now is getting overall coverage, and
# testing that .


def test_cli_cluster_cat_indices(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'cat_indices')
    api_mock = mocker.patch.object(Cluster, 'call_api')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'cat-indices'],
                           catch_exceptions=True)
    # Should have been called two times.
    middleware_mock.assert_called()
    api_mock.assert_called()
    assert result.exit_code == 0
    assert 'SOURCE CLUSTER' in result.output
    assert 'TARGET CLUSTER' in result.output


def test_cli_cluster_cat_indices_as_json(runner, mocker):
    mock = mocker.patch('console_link.middleware.clusters.cat_indices', return_value={'index': 'data'}, autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), '--json', 'clusters', 'cat-indices'],
                           catch_exceptions=True)
    # Should have been called two times.
    mock.assert_called()
    assert result.exit_code == 0
    assert json.loads(result.output).keys() == {'source_cluster', 'target_cluster'}


def test_cli_cluster_connection_check(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'connection_check')
    api_mock = mocker.patch.object(Cluster, 'call_api')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'connection-check'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    assert 'SOURCE CLUSTER' in result.output
    assert 'TARGET CLUSTER' in result.output
    # Should have been called two times.
    middleware_mock.assert_called()
    api_mock.assert_called()


def test_cli_cluster_cat_indices_and_connection_check_with_one_cluster(runner, mocker,
                                                                       target_cluster_only_yaml_path,
                                                                       source_cluster_only_yaml_path):
    middleware_connection_check_mock = mocker.spy(middleware.clusters, 'connection_check')
    middleware_cat_indices_mock = mocker.spy(middleware.clusters, 'cat_indices')
    api_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    # Connection check with no target cluster
    result = runner.invoke(cli, ['--config-file', str(source_cluster_only_yaml_path), 'clusters', 'connection-check'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    assert "SOURCE CLUSTER" in result.output
    assert "No target cluster defined." in result.output
    middleware_connection_check_mock.assert_called_once()
    api_mock.assert_called_once()
    middleware_connection_check_mock.reset_mock()
    api_mock.reset_mock()
    # Connection check with no source cluster
    result = runner.invoke(cli, ['--config-file', str(target_cluster_only_yaml_path), 'clusters', 'connection-check'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    assert "TARGET CLUSTER" in result.output
    assert "No source cluster defined." in result.output
    middleware_connection_check_mock.assert_called_once()
    api_mock.assert_called_once()
    middleware_connection_check_mock.reset_mock()
    api_mock.reset_mock()
    # Cat indices with no target cluster
    result = runner.invoke(cli, ['--config-file', str(source_cluster_only_yaml_path), 'clusters', 'cat-indices'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    assert "SOURCE CLUSTER" in result.output
    assert "No target cluster defined." in result.output
    middleware_cat_indices_mock.assert_called_once()
    api_mock.assert_called_once()
    middleware_cat_indices_mock.reset_mock()
    api_mock.reset_mock()
    # Cat indices with no source cluster
    result = runner.invoke(cli, ['--config-file', str(target_cluster_only_yaml_path), 'clusters', 'cat-indices'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    assert "TARGET CLUSTER" in result.output
    assert "No source cluster defined." in result.output
    middleware_cat_indices_mock.assert_called_once()
    api_mock.assert_called_once()


def test_cli_cluster_run_test_benchmarks(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'run_test_benchmarks')
    model_mock = mocker.patch.object(Cluster, 'execute_benchmark_workload')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'run-test-benchmarks'],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called()
    assert result.exit_code == 0


def test_cli_cluster_run_test_benchmarks_without_source_raises_error(runner, mocker, target_cluster_only_yaml_path):
    middleware_mock = mocker.spy(middleware.clusters, 'run_test_benchmarks')
    model_mock = mocker.patch.object(Cluster, 'execute_benchmark_workload')
    result = runner.invoke(cli, ['--config-file', target_cluster_only_yaml_path, 'clusters', 'run-test-benchmarks'],
                           catch_exceptions=True)
    middleware_mock.assert_not_called()
    model_mock.assert_not_called()
    assert result.exit_code == 2


def test_cli_cluster_run_curl_source_cluster(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    json_body = '{"id": 3, "number": 5}'
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'source_cluster', 'new_index/_doc', '-XPOST', '--json', json_body],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called_once()
    assert model_mock.call_args.kwargs == {'path': '/new_index/_doc', 'method': HttpMethod.POST,
                                           'data': '{"id": 3, "number": 5}',
                                           'headers': {'Content-Type': 'application/json'},
                                           'timeout': None, 'session': None, 'raise_error': False}
    assert result.exit_code == 0


def test_cli_cluster_run_curl_target_cluster(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'target_cluster', '/_cat/indices', '-XGET', '-H', 'user-agent:TestAgent'],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called_once()
    assert model_mock.call_args.kwargs == {'path': '/_cat/indices', 'method': HttpMethod.GET,
                                           'data': None, 'headers': {'user-agent': 'TestAgent'}, 'timeout': None,
                                           'session': None, 'raise_error': False}
    assert result.exit_code == 0


def test_cli_cluster_run_curl_undefined_cluster(runner, mocker, source_cluster_only_yaml_path):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(source_cluster_only_yaml_path), 'clusters', 'curl',
                                 'target_cluster', '/_cat/indices'],
                           catch_exceptions=True)
    middleware_mock.assert_not_called()
    model_mock.assert_not_called()
    assert result.exit_code == 2


def test_cli_cluster_run_curl_bad_json(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    malformed_json = '{"id": 3, "number": "5}'
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'source_cluster', '/new-index', '-XPOST', '--json', malformed_json],
                           catch_exceptions=True)
    middleware_mock.assert_not_called()
    model_mock.assert_not_called()
    assert result.exit_code == 2


def test_cli_cluster_run_curl_bad_headers(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'source_cluster', '/new-index', '-H', 'key=value'],
                           catch_exceptions=True)
    middleware_mock.assert_not_called()
    model_mock.assert_not_called()
    assert result.exit_code == 2


def test_cli_cluster_run_curl_multiple_headers(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    headers = [('key1', 'value1'), ('key2', 'value2')]
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'target_cluster', '/', '-H', f"{headers[0][0]}:{headers[0][1]}",
                                 "-H", f"{headers[1][0]}:{headers[1][1]}"],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called_once()
    assert model_mock.call_args.kwargs == {'path': '/', 'method': HttpMethod.GET,
                                           'data': None, 'headers': {k: v for k, v in headers}, 'timeout': None,
                                           'session': None, 'raise_error': False}
    assert result.exit_code == 0


def test_cli_cluster_run_curl_head_method(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'target_cluster', '/', '-X', 'HEAD'],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called_once()
    assert model_mock.call_args.kwargs == {'path': '/', 'method': HttpMethod.HEAD,
                                           'data': None, 'headers': {}, 'timeout': None,
                                           'session': None, 'raise_error': False}
    assert result.exit_code == 0


def test_cli_cluster_clear_indices(runner, mocker):
    mock = mocker.patch('console_link.middleware.clusters.clear_indices')
    result = runner.invoke(cli,
                           ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'clear-indices',
                            '--cluster', 'source', '--acknowledge-risk'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_cluster_clear_indices_no_acknowledge(runner, mocker):
    mock = mocker.patch('console_link.middleware.clusters.clear_indices')
    runner.invoke(cli,
                  ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'clear-indices',
                   '--cluster', 'source'],
                  catch_exceptions=True)
    assert not mock.called


source_cat_indices = """
green  open logs-221998             pKNVNlhcRuuUXlwPJag9Kg 5 0 1000 0 167.4kb 167.4kb
green  open geonames                DlT1Qp-7SqaARuECxTaYvw 5 0 1000 0 343.2kb 343.2kb
yellow open sg7-auditlog-2024.06.12 Ih0JZg_eQV6gNXRPsAf73w 1 1  128 0  55.7kb  55.7kb
"""
target_cat_indices = """
green  open logs-221998                  x_gytR5_SCCwsSf0ydcFrw 5 0 1000 0 153.7kb 153.7kb
green  open geonames                     Q96YGsvlQ-6hcZvMNzyyDg 5 0 1000 0 336.2kb 336.2kb
green  open reindexed-logs               2queREGZRriWNZ9ukMvsuw 5 0    0 0     1kb     1kb
green  open nyc_taxis                    j1HSbvtGRbG7H7SlJXrB0g 1 0 1000 0 159.3kb 159.3kb
"""


def test_cli_cat_indices_e2e(runner, env):
    with requests_mock.Mocker() as rm:
        rm.get(f"{env.source_cluster.endpoint}/_cat/indices/_all",
               status_code=200,
               text=source_cat_indices)
        rm.get(f"{env.target_cluster.endpoint}/_cat/indices/_all",
               status_code=200,
               text=target_cat_indices)
        result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'cat-indices'],
                               catch_exceptions=True)

    assert result.exit_code == 0
    assert 'SOURCE CLUSTER' in result.output
    assert 'TARGET CLUSTER' in result.output
    assert source_cat_indices in result.output
    assert target_cat_indices in result.output


def test_cli_snapshot_when_not_defined_raises_error(runner, source_cluster_only_yaml_path):
    result = runner.invoke(cli, ['--config-file', source_cluster_only_yaml_path, 'snapshot', 'create'],
                           catch_exceptions=True)
    assert result.exit_code == 2
    assert "Snapshot is not set" in result.output


def test_cli_snapshot_create(runner, mocker):
    mock = mocker.patch('console_link.middleware.snapshot.create')

    # Set the mock return value
    mock.return_value = CommandResult(success=True, value="Snapshot created successfully.")

    # Test snapshot creation
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'snapshot', 'create'],
                           catch_exceptions=True)

    assert result.exit_code == 0
    assert "Snapshot created successfully" in result.output

    # Ensure the mocks were called
    mock.assert_called_once()


def test_cli_snapshot_create_with_extra_args(runner, mocker):
    mock = mocker.patch('subprocess.run', autospec=True)

    extra_args = ['--extra-flag', '--extra-arg', 'extra-arg-value', 'this-is-an-option']
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'snapshot', 'create'] + extra_args,
                           catch_exceptions=True)

    assert result.exit_code == 0
    mock.assert_called_once()
    assert all([arg in mock.call_args.args[0] for arg in extra_args])


def test_cli_snapshot_status(runner, mocker):
    mock = mocker.patch('console_link.middleware.snapshot.status')

    # Set the mock return value
    mock.return_value = CommandResult(success=True, value="Snapshot status: COMPLETED")

    # Test snapshot status
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'snapshot', 'status'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    assert "Snapshot status: COMPLETED" in result.output

    # Ensure the mocks were called
    mock.assert_called_once()


def test_cli_snapshot_delete_with_acknowledgement(runner, mocker):
    mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    mock.return_value.text = "Successfully deleted"

    # Test snapshot status
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'snapshot', 'delete', '--acknowledge-risk'],
                           catch_exceptions=True)
    assert result.exit_code == 0

    # Ensure the mocks were called
    mock.assert_called_once()


def test_cli_snapshot_delete_without_acknowledgement_doesnt_run(runner, mocker):
    mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    mock.return_value.text = "Successfully deleted"

    # Test snapshot status
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'snapshot', 'delete'], input="n",
                           catch_exceptions=True)
    assert result.exit_code == 0

    # Ensure the mocks were called
    mock.assert_not_called()


def test_cli_snapshot_unregister_repo_with_acknowledgement(runner, mocker):
    mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    mock.return_value.text = "Successfully deleted"

    # Test snapshot status
    result = runner.invoke(cli, ['--config-file',
                                 str(VALID_SERVICES_YAML),
                                 'snapshot',
                                 'unregister-repo',
                                 '--acknowledge-risk'],
                           catch_exceptions=True)
    assert result.exit_code == 0

    # Ensure the mocks were called
    mock.assert_called_once()


def test_cli_snapshot_unregister_repo_without_acknowledgement_doesnt_run(runner, mocker):
    mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    mock.return_value.text = "Successfully deleted"

    # Test snapshot status
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'snapshot', 'unregister-repo'], input="n",
                           catch_exceptions=True)
    assert result.exit_code == 0

    # Ensure the mocks were not called
    mock.assert_not_called()


def test_cli_with_backfill_describe(runner, mocker):
    mock = mocker.patch('console_link.middleware.backfill.describe')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'backfill', 'describe'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_backfill_when_not_defined(runner, source_cluster_only_yaml_path):
    result = runner.invoke(cli, ['--config-file', source_cluster_only_yaml_path, 'backfill', 'start'],
                           catch_exceptions=True)
    assert result.exit_code == 2
    assert "Backfill migration is not set" in result.output


def test_cli_backfill_start(runner, mocker):
    mock = mocker.patch.object(ECSRFSBackfill, 'start', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'start'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_backfill_pause(runner, mocker):
    mock = mocker.patch.object(ECSRFSBackfill, 'pause', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'pause'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_backfill_stop(runner, mocker):
    mock_stop = mocker.patch.object(ECSRFSBackfill, 'stop', autospec=True)

    archive_result_seq = [
        CommandResult(success=False, value=RfsWorkersInProgress()),
        CommandResult(success=True, value="/path/to/archive.json")
    ]
    mock_archive = mocker.patch.object(ECSRFSBackfill, 'archive', autospec=True, side_effect=archive_result_seq)
    mocker.patch.object(time, 'sleep', autospec=True)  # make a no-op

    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'stop'],
                           catch_exceptions=False)
    mock_stop.assert_called_once()
    assert mock_archive.call_count == 2
    assert result.exit_code == 0


def test_cli_backfill_stop_no_index(runner, mocker):
    mock_stop = mocker.patch.object(ECSRFSBackfill, 'stop', autospec=True)

    archive_result = CommandResult(success=False, value=WorkingIndexDoesntExist("index"))
    mock_archive = mocker.patch.object(ECSRFSBackfill, 'archive', autospec=True, return_value=archive_result)
    mocker.patch.object(time, 'sleep', autospec=True)  # make a no-op

    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'stop'],
                           catch_exceptions=False)
    mock_stop.assert_called_once()
    assert mock_archive.call_count == 1
    assert result.exit_code == 0


def test_cli_backfill_scale(runner, mocker):
    mock = mocker.patch.object(ECSRFSBackfill, 'scale', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'scale', '3'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_backfill_scale_with_no_units_fails(runner, mocker):
    mock = mocker.patch.object(ECSRFSBackfill, 'scale', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'scale'],
                           catch_exceptions=True)
    mock.assert_not_called()
    assert result.exit_code == 2
    print(result.output)


def test_get_backfill_status_no_deep_check(runner, mocker):
    mocked_running_status = DeploymentStatus(
        desired=1,
        running=3,
        pending=1
    )
    mock = mocker.patch.object(ECSService, 'get_instance_statuses', autspec=True, return_value=mocked_running_status)

    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'status'],
                           catch_exceptions=True)
    print(result)
    print(result.output)
    assert result.exit_code == 0
    assert "RUNNING" in result.output
    assert str(mocked_running_status) in result.output

    mock.assert_called_once()


def test_get_backfill_status_with_deep_check(runner, mocker):
    mocked_running_status = DeploymentStatus(
        desired=1,
        running=3,
        pending=1
    )
    mocked_detailed_status = "Remaining shards: 43"
    mock_ecs_service_call = mocker.patch.object(ECSService, 'get_instance_statuses', autspec=True,
                                                return_value=mocked_running_status)
    mock_detailed_status_call = mocker.patch('console_link.models.backfill_rfs.get_detailed_status', autspec=True,
                                             return_value=mocked_detailed_status)

    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'status', '--deep-check'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    assert "RUNNING" in result.output
    assert str(mocked_running_status) in result.output
    assert mocked_detailed_status in result.output

    mock_ecs_service_call.assert_called_once()
    mock_detailed_status_call.assert_called_once()


def test_cli_replay_when_not_defined(runner, source_cluster_only_yaml_path):
    result = runner.invoke(cli, ['--config-file', source_cluster_only_yaml_path, 'replay', 'describe'],
                           catch_exceptions=True)
    assert result.exit_code == 2
    assert "Replay is not set" in result.output


def test_replay_describe(runner, mocker):
    mock = mocker.patch('console_link.middleware.replay.describe')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'replay', 'describe'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_replay_start(runner, mocker):
    mock = mocker.patch.object(ECSReplayer, 'start', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'replay', 'start'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_replay_stop(runner, mocker):
    mock = mocker.patch.object(ECSReplayer, 'stop', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'replay', 'stop'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_replay_scale(runner, mocker):
    mock = mocker.patch.object(ECSReplayer, 'scale', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'replay', 'scale', '5'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_replay_scale_with_no_units_fails(runner, mocker):
    mock = mocker.patch.object(ECSReplayer, 'scale', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'replay', 'scale'],
                           catch_exceptions=True)
    mock.assert_not_called()
    assert result.exit_code == 2


def test_replay_status(runner, mocker):
    mock = mocker.patch.object(ECSReplayer, 'get_status', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'replay', 'status'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_metadata_when_not_defined(runner, source_cluster_only_yaml_path):
    result = runner.invoke(cli, ['--config-file', source_cluster_only_yaml_path, 'metadata', 'migrate'],
                           catch_exceptions=True)
    assert result.exit_code == 2
    assert "Metadata is not set" in result.output


def test_cli_metadata_migrate(runner, mocker):
    mock_subprocess_result = CompletedProcess(args=[], returncode=0, stdout="Command successful", stderr=None)
    mock = mocker.patch("subprocess.run", return_value=mock_subprocess_result)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'metadata', 'migrate'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_metadata_evaluate(runner, mocker):
    mock_subprocess_result = CompletedProcess(args=[], returncode=0, stdout="Command successful", stderr=None)
    mock = mocker.patch("subprocess.run", return_value=mock_subprocess_result)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'metadata', 'evaluate'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_metrics_when_not_defined(runner, source_cluster_only_yaml_path):
    result = runner.invoke(cli, ['--config-file', source_cluster_only_yaml_path, 'metrics', 'list'],
                           catch_exceptions=True)
    assert result.exit_code == 2
    assert "Metrics source is not set" in result.output


def test_cli_with_metrics_list_metrics(runner, mocker):
    mock = mocker.patch('console_link.models.metrics_source.PrometheusMetricsSource.get_metrics')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'metrics', 'list'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_with_metrics_list_metrics_as_json(runner, mocker):
    mock = mocker.patch('console_link.models.metrics_source.PrometheusMetricsSource.get_metrics',
                        return_value={'captureProxy': ['kafkaCommitCount', 'captureConnectionDuration'],
                                      'replayer': ['kafkaCommitCount']}, autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), '--json', 'metrics', 'list'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_with_metrics_get_data(runner, mocker):
    mock = mocker.patch('console_link.models.metrics_source.PrometheusMetricsSource.get_metric_data',
                        return_value=[('2024-05-22T20:06:00+00:00', 0.0), ('2024-05-22T20:07:00+00:00', 1.0),
                                      ('2024-05-22T20:08:00+00:00', 2.0), ('2024-05-22T20:09:00+00:00', 3.0),
                                      ('2024-05-22T20:10:00+00:00', 4.0)],
                        autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'metrics', 'get-data',
                                 'replayer', 'kafkaCommitCount'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    mock.assert_called_once()
    assert mock.call_args.args[1] == Component.REPLAYER
    assert mock.call_args.args[2] == 'kafkaCommitCount'


def test_cli_with_metrics_get_data_as_json(runner, mocker):
    mock = mocker.patch('console_link.models.metrics_source.PrometheusMetricsSource.get_metric_data',
                        return_value=[('2024-05-22T20:06:00+00:00', 0.0), ('2024-05-22T20:07:00+00:00', 1.0),
                                      ('2024-05-22T20:08:00+00:00', 2.0), ('2024-05-22T20:09:00+00:00', 3.0),
                                      ('2024-05-22T20:10:00+00:00', 4.0)],
                        autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), '--json', 'metrics', 'get-data',
                                 'replayer', 'kafkaCommitCount'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    mock.assert_called_once()
    assert mock.call_args.args[1] == Component.REPLAYER
    assert mock.call_args.args[2] == 'kafkaCommitCount'


def test_cli_kafka_when_not_defined(runner, source_cluster_only_yaml_path):
    result = runner.invoke(cli, ['--config-file', source_cluster_only_yaml_path, 'kafka', 'create-topic'],
                           catch_exceptions=True)
    assert result.exit_code == 2
    assert "Kafka is not set" in result.output


def test_cli_kafka_create_topic(runner, mocker):
    middleware_mock = mocker.spy(middleware.kafka, 'create_topic')
    model_mock = mocker.patch.object(StandardKafka, 'create_topic')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'create-topic',
                                 '--topic-name', 'test'],
                           catch_exceptions=True)

    model_mock.assert_called_once_with(topic_name='test')
    middleware_mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_kafka_delete_topic(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'delete_topic')
    middleware_mock = mocker.spy(middleware.kafka, 'delete_topic')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'delete-topic',
                                 '--topic-name', 'test', '--acknowledge-risk'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(topic_name='test')
    middleware_mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_kafka_describe_consumer_group(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'describe_consumer_group')
    middleware_mock = mocker.spy(middleware.kafka, 'describe_consumer_group')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'describe-consumer-group',
                                 '--group-name', 'test-group'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(group_name='test-group')
    middleware_mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_kafka_describe_topic(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'describe_topic_records')
    middleware_mock = mocker.spy(middleware.kafka, 'describe_topic_records')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'describe-topic-records',
                                 '--topic-name', 'test'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(topic_name='test')
    middleware_mock.assert_called_once()
    assert result.exit_code == 0


def test_completion_script(runner):
    result = runner.invoke(
        cli,
        ['--config-file', str(VALID_SERVICES_YAML), 'completion', 'bash'],
        catch_exceptions=True
    )
    assert result.exit_code == 0


def test_tuple_converter(runner, tmp_path):
    # The `multiple_tuples` and `multiple_tuples_parsed` files are formatted as "real" json objects so that
    # they can be pretty-printed and examined, but they need to be converted to ndjson files to be used by the
    # CLI command.

    # Make the input file
    input_tuples_file = f"{TEST_DATA_DIRECTORY}/multiple_tuples.json"
    with open(input_tuples_file, 'r') as f:
        input_tuples = json.load(f)
    ndjson_input_file = f"{tmp_path}/tuples.ndjson"
    with open(ndjson_input_file, 'w') as f:
        f.write('\n'.join([json.dumps(record) for record in input_tuples]))

    ndjson_output_file = f"{tmp_path}/converted_tuples.ndjson"
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'tuples', 'show',
                                 '--in', ndjson_input_file,
                                 '--out', ndjson_output_file],
                           catch_exceptions=True)
    assert ndjson_output_file in result.output
    assert result.exit_code == 0

    # Open the ndjson output file and compare it to the "real" output file
    expected_output_file = f"{TEST_DATA_DIRECTORY}/multiple_tuples_parsed.json"
    with open(expected_output_file, 'r') as f:
        output_tuples = json.load(f)
    expected_output_as_ndjson = [json.dumps(record) + "\n" for record in output_tuples]

    assert open(ndjson_output_file).readlines() == expected_output_as_ndjson
