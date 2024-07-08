import json
import pathlib
import os

import pytest
import requests_mock
from click.testing import CliRunner

import console_link.middleware as middleware
from console_link.cli import cli
from console_link.environment import Environment
from console_link.models.backfill_rfs import ECSRFSBackfill
from console_link.models.cluster import Cluster
from console_link.models.command_result import CommandResult
from console_link.models.ecs_service import ECSService, InstanceStatuses
from console_link.models.kafka import StandardKafka
from console_link.models.replayer_ecs import ECSReplayer

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

# Tests around the general CLI functionality


def test_cli_without_valid_services_file_raises_error(runner):
    result = runner.invoke(cli, ['--config-file', '~/non-existent/file/services.yaml', 'clusters', 'cat-indices'])
    assert result.exit_code == 1
    assert " No such file or directory: '~/non-existent/file/services.yaml'" in result.stdout
    assert isinstance(result.exception, SystemExit)


def test_cli_with_valid_services_file_does_not_raise_error(runner):
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'backfill', 'describe'],
                           catch_exceptions=True)
    assert result.exit_code == 0


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


def test_cli_cluster_run_test_benchmarks(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'run_test_benchmarks')
    model_mock = mocker.patch.object(Cluster, 'execute_benchmark_workload')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'run-test-benchmarks'],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called()
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


def test_cli_with_backfill_describe(runner, mocker):
    mock = mocker.patch('console_link.middleware.backfill.describe')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'backfill', 'describe'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_backfill_create_rfs(runner, mocker):
    mock = mocker.patch.object(ECSRFSBackfill, 'create', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'create'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_backfill_start(runner, mocker):
    mock = mocker.patch.object(ECSRFSBackfill, 'start', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'start'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_backfill_stop(runner, mocker):
    mock = mocker.patch.object(ECSRFSBackfill, 'stop', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'stop'],
                           catch_exceptions=True)
    mock.assert_called_once()
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
    mocked_running_status = InstanceStatuses(
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
    mocked_running_status = InstanceStatuses(
        desired=1,
        running=3,
        pending=1
    )
    mocked_detailed_status = "Remaining shards: 43"
    mock_ecs_service_call = mocker.patch.object(ECSService, 'get_instance_statuses', autspec=True,
                                                return_value=mocked_running_status)
    mock_detailed_status_call = mocker.patch.object(ECSRFSBackfill, '_get_detailed_status', autspec=True,
                                                    return_value=mocked_detailed_status)

    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'status', '--deep-check'],
                           catch_exceptions=True)
    print(result)
    print(result.output)
    assert result.exit_code == 0
    assert "RUNNING" in result.output
    assert str(mocked_running_status) in result.output
    assert mocked_detailed_status in result.output

    mock_ecs_service_call.assert_called_once()
    mock_detailed_status_call.assert_called_once()


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


def test_cli_metadata_migrate(runner, mocker):
    mock = mocker.patch("subprocess.run")
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'metadata', 'migrate'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_with_metrics_get_data(runner, mocker):
    mock = mocker.patch('console_link.models.metrics_source.PrometheusMetricsSource.get_metrics')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'metrics', 'list'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_kafka_create_topic(runner, mocker):
    # These commands _should_ go through the middleware layer but currently don't
    # middleware_mock = mocker.spy(middleware.kafka, 'create_topic')
    # middleware_mock.assert_called_once_with(env.kafka, 'test')

    model_mock = mocker.patch.object(StandardKafka, 'create_topic')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'create-topic',
                                 '--topic-name', 'test'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(topic_name='test')
    assert result.exit_code == 0


def test_cli_kafka_delete_topic(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'delete_topic')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'delete-topic',
                                 '--topic-name', 'test', '--acknowledge-risk'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(topic_name='test')
    assert result.exit_code == 0


def test_cli_kafka_describe_consumer_group(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'describe_consumer_group')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'describe-consumer-group',
                                 '--group-name', 'test-group'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(group_name='test-group')
    assert result.exit_code == 0


def test_cli_kafka_describe_topic(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'describe_topic_records')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'describe-topic-records',
                                 '--topic-name', 'test'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(topic_name='test')
    assert result.exit_code == 0
