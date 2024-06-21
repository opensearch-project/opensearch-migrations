import pathlib
from console_link.models.snapshot import SnapshotStatus

import requests_mock

from console_link.cli import cli
from console_link.environment import Environment

from click.testing import CliRunner
import pytest


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


def test_cli_without_valid_services_file_raises_error(runner):
    result = runner.invoke(cli, ['--config-file', '~/non-existent/file/services.yaml', 'clusters', 'cat-indices'])
    assert result.exit_code == 1
    assert " No such file or directory: '~/non-existent/file/services.yaml'" in result.stdout
    assert isinstance(result.exception, SystemExit)


def test_cli_with_valid_services_file_does_not_raise_error(runner):
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'backfill', 'describe'],
                           catch_exceptions=True)
    assert result.exit_code == 0


def test_cli_cluster_cat_indices(runner, env, mocker):
    mock = mocker.patch('console_link.logic.clusters.cat_indices')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'cat-indices'],
                           catch_exceptions=True)
    # Should have been called two times.
    assert result.exit_code == 0
    assert 'SOURCE CLUSTER' in result.output
    assert 'TARGET CLUSTER' in result.output
    mock.assert_called()

def test_cli_cluster_connection_check(runner, env, mocker):
    mock = mocker.patch('console_link.logic.clusters.connection_check')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'connection-check'],
                           catch_exceptions=True)
    # Should have been called two times.
    assert result.exit_code == 0
    assert 'SOURCE CLUSTER' in result.output
    assert 'TARGET CLUSTER' in result.output
    mock.assert_called()

def test_cli_cluster_run_test_benchmarks(runner, env, mocker):
    mock = mocker.patch('console_link.logic.clusters.run_test_benchmarks')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'run-test-benchmarks'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0

def test_cli_cluster_clear_indices(runner, env, mocker):
    mock = mocker.patch('console_link.logic.clusters.clear_indices')
    result = runner.invoke(cli,
                           ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'clear-indices',
                            '--cluster', 'source', '--acknowledge-risk'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0

def test_cli_with_metrics_get_data(runner, env, mocker):
    mock = mocker.patch('console_link.models.metrics_source.PrometheusMetricsSource.get_metrics')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'metrics', 'list'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_with_backfill_describe(runner, env, mocker):
    mock = mocker.patch('console_link.logic.backfill.describe')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'backfill', 'describe'],
                           catch_exceptions=True)
    mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_snapshot_create(runner, env, mocker):
    mock = mocker.patch('console_link.logic.snapshot.create')

    # Set the mock return value
    mock.return_value = SnapshotStatus.COMPLETED, "Snapshot created successfully."

    # Test snapshot creation
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'snapshot', 'create'],
                           catch_exceptions=True)

    assert result.exit_code == 0
    assert "Snapshot created successfully" in result.output

    # Ensure the mocks were called
    mock.assert_called_once()


@pytest.mark.skip(reason="Not implemented yet")
def test_cli_snapshot_status(runner, env, mocker):
    mock = mocker.patch('console_link.logic.snapshot.status')

    # Set the mock return value
    mock.return_value = SnapshotStatus.COMPLETED, "Snapshot status: COMPLETED"

    # Test snapshot status
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'snapshot', 'status'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    assert "Snapshot status: COMPLETED" in result.output

    # Ensure the mocks were called
    mock.assert_called_once()


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
        rm.get(f"{env.source_cluster.endpoint}/_cat/indices",
               status_code=200,
               text=source_cat_indices)
        rm.get(f"{env.target_cluster.endpoint}/_cat/indices",
               status_code=200,
               text=target_cat_indices)
        result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'cat-indices'],
                               catch_exceptions=True)

    assert result.exit_code == 0
    assert 'SOURCE CLUSTER' in result.output
    assert 'TARGET CLUSTER' in result.output
    assert source_cat_indices in result.output
    assert target_cat_indices in result.output
