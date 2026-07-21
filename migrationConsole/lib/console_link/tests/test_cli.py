import json
import logging
import pathlib
import os
import time
import pytest
import requests_mock
from click.testing import CliRunner
from types import SimpleNamespace
from subprocess import CompletedProcess

import console_link.middleware as middleware
import console_link.cli as cli_module
from console_link.cli import (
    cli,
    get_kafka_consumer_group_completions,
    get_kafka_topic_completions,
    main,
)
from console_link.environment import Environment
from console_link.k8s_resource_catalog import (
    ConsoleConsumerGroupEntry,
    ConsoleResourceCatalog,
    ConsoleResourceEntry,
    ResourceRole,
)
from console_link.models.backfill_rfs import ECSRFSBackfill, RfsWorkersInProgress, WorkingIndexDoesntExist
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.command_result import CommandResult
from console_link.models.ecs_service import ECSService
from console_link.models.kafka import StandardKafka
from console_link.models.metrics_source import Component
from console_link.models.replayer_ecs import ECSReplayer
from console_link.models.snapshot import FileSystemSnapshot
from console_link.models.utils import DeploymentStatus

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
VALID_SERVICES_YAML = TEST_DATA_DIRECTORY / "services.yaml"


def _catalog_cluster_config(endpoint):
    return {
        "endpoint": endpoint,
        "allow_insecure": True,
        "no_auth": None,
    }


def _catalog_kafka_entry(name, broker):
    return ConsoleResourceEntry(
        ResourceRole.KAFKA,
        name,
        [name],
        kafka_runtime={
            "type": "direct",
            "clientConfig": {"broker_endpoints": broker, "standard": None},
        },
    )


def _catalog_env(entries, consumer_groups=None):
    catalog = ConsoleResourceCatalog(entries)
    catalog.consumer_groups = consumer_groups or []
    group_names = [
        group.name if hasattr(group, "name") else group
        for group in (consumer_groups or [])
    ]
    return SimpleNamespace(
        resources=catalog,
        client_options=None,
        source_cluster=None,
        target_cluster=None,
        proxy=None,
        kafka=None,
        kafka_consumer_groups=group_names,
    )


@pytest.fixture
def runner():
    """A CliRunner for the cli function"""
    runner = CliRunner()
    return runner


@pytest.fixture
def env():
    """A valid Environment for the given VALID_SERVICES_YAML file"""
    return Environment(config_file=VALID_SERVICES_YAML)


@pytest.fixture(autouse=True)
def block_k8s_config_store_usage(monkeypatch):
    # If these tests are running on a computer with k8s, they may be able to instantiate
    # the WorkflowConfigStore and believe they are running in a k8s environment, which causes
    # it to ignore any `services.yaml`-type config files. This fixture caues the `can_use_k8s_config_store`
    # check to always return False.
    monkeypatch.setattr(cli_module, "can_use_k8s_config_store", lambda: False)


@pytest.fixture(autouse=True)
def set_fake_aws_credentials():
    # These are example credentials from
    # https://docs.aws.amazon.com/IAM/latest/UserGuide/security-creds.html#sec-access-keys-and-secret-access-keys
    # They allow the boto client to be created for any AWS services, but functions must be intercepted
    # before any real calls are made.
    os.environ['AWS_ACCESS_KEY_ID'] = 'AKIAIOSFODNN7EXAMPLE'
    os.environ['AWS_SECRET_ACCESS_KEY'] = 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY'
    os.environ.setdefault('AWS_DEFAULT_REGION', 'us-east-1')


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


@pytest.fixture
def proxy_enabled_yaml_path(tmp_path):
    proxy_enabled_path = tmp_path / "proxy_enabled.yaml"
    proxy_enabled_yaml = """
source_cluster:
  endpoint: "https://elasticsearch:9200"
  allow_insecure: true
  basic_auth:
    username: "admin"
    password: "admin"
  proxy:
    name: "capture-proxy"
    endpoint: "http://capture-proxy:9201"
    allow_insecure: true
target_cluster:
  endpoint: "https://opensearchtarget:9200"
  allow_insecure: true
  basic_auth:
    username: "admin"
    password: "myStrongPassword123!"
"""
    with open(proxy_enabled_path, 'w') as f:
        f.write(proxy_enabled_yaml)
    return proxy_enabled_path

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
  otel_metrics_endpoint: "http://otel-collector:4317"
  """
    yaml_path = tmp_path / "services.yaml"
    with open(yaml_path, 'w') as f:
        f.write(no_cluster_services)

    result = runner.invoke(cli, ['--config-file', str(yaml_path), 'clusters', 'connection-check'],
                           catch_exceptions=True)
    assert result.exit_code == 1
    assert isinstance(result.exception, SystemExit)


def test_cli_snapshot_when_source_cluster_not_defined(runner, tmp_path):
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
  otel_metrics_endpoint: "http://otel-collector:4317"
  """
    yaml_path = tmp_path / "services.yaml"
    with open(yaml_path, 'w') as f:
        f.write(no_source_cluster_with_snapshot)

    result = runner.invoke(cli, ['--config-file', str(yaml_path), 'snapshot', 'create'],
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


def test_cli_cluster_cat_indices_proxy(runner, mocker, proxy_enabled_yaml_path):
    middleware_mock = mocker.spy(middleware.clusters, 'cat_indices')
    api_mock = mocker.patch.object(Cluster, 'call_api')
    result = runner.invoke(cli, ['--config-file', str(proxy_enabled_yaml_path), 'clusters', 'cat-indices',
                                 '--cluster', 'proxy'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    middleware_mock.assert_called_once()
    api_mock.assert_called_once()


def test_cli_cluster_cat_indices_requires_selectors_for_ambiguous_k8s_resources(runner, mocker):
    env = _catalog_env([
        ConsoleResourceEntry(ResourceRole.SOURCE, "source1", ["source1"],
                             client_config=_catalog_cluster_config("https://source1.example.com")),
        ConsoleResourceEntry(ResourceRole.SOURCE, "source2", ["source2"],
                             client_config=_catalog_cluster_config("https://source2.example.com")),
        ConsoleResourceEntry(ResourceRole.TARGET, "target1", ["target1"],
                             client_config=_catalog_cluster_config("https://target1.example.com")),
        ConsoleResourceEntry(ResourceRole.TARGET, "target2", ["target2"],
                             client_config=_catalog_cluster_config("https://target2.example.com")),
    ])
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    cat_indices = mocker.patch('console_link.middleware.clusters.cat_indices', return_value='indices')

    result = runner.invoke(cli, ['clusters', 'cat-indices'], catch_exceptions=True)

    assert result.exit_code == 2
    assert "Multiple source resources are configured" in result.output
    assert "Specify: --source <source1|source2> --target <target1|target2>." in result.output
    cat_indices.assert_not_called()


def test_cli_cluster_cat_indices_uses_selected_k8s_resources(runner, mocker):
    env = _catalog_env([
        ConsoleResourceEntry(ResourceRole.SOURCE, "source1", ["source1"],
                             client_config=_catalog_cluster_config("https://source1.example.com")),
        ConsoleResourceEntry(ResourceRole.SOURCE, "source2", ["source2"],
                             client_config=_catalog_cluster_config("https://source2.example.com")),
        ConsoleResourceEntry(ResourceRole.TARGET, "target1", ["target1"],
                             client_config=_catalog_cluster_config("https://target1.example.com")),
    ])
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    cat_indices = mocker.patch('console_link.middleware.clusters.cat_indices', return_value='indices')

    result = runner.invoke(
        cli,
        ['clusters', 'cat-indices', '--source', 'source2', '--target', 'target1'],
        catch_exceptions=True,
    )

    assert result.exit_code == 0
    assert [call.args[0].endpoint for call in cat_indices.call_args_list] == [
        "https://source2.example.com",
        "https://target1.example.com",
    ]


def test_cli_cluster_clear_indices_accepts_concrete_k8s_cluster_selector(runner, mocker):
    env = _catalog_env([
        ConsoleResourceEntry(ResourceRole.SOURCE, "source1", ["source1"],
                             client_config=_catalog_cluster_config("https://source1.example.com")),
        ConsoleResourceEntry(ResourceRole.SOURCE, "source2", ["source2"],
                             client_config=_catalog_cluster_config("https://source2.example.com")),
        ConsoleResourceEntry(ResourceRole.TARGET, "target1", ["target1"],
                             client_config=_catalog_cluster_config("https://target1.example.com")),
    ])
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    clear_indices = mocker.patch('console_link.middleware.clusters.clear_indices', return_value='cleared')

    result = runner.invoke(
        cli,
        ['clusters', 'clear-indices', '--cluster', 'source2', '--acknowledge-risk'],
        catch_exceptions=True,
    )

    assert result.exit_code == 0
    clear_indices.assert_called_once()
    assert clear_indices.call_args.args[0].endpoint == "https://source2.example.com"


def test_cli_cluster_clear_indices_role_shorthand_auto_selects_single_k8s_cluster(runner, mocker):
    env = _catalog_env([
        ConsoleResourceEntry(ResourceRole.SOURCE, "source-a", ["source-a"],
                             client_config=_catalog_cluster_config("https://source-a.example.com")),
        ConsoleResourceEntry(ResourceRole.TARGET, "target-a", ["target-a"],
                             client_config=_catalog_cluster_config("https://target-a.example.com")),
    ])
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    clear_indices = mocker.patch('console_link.middleware.clusters.clear_indices', return_value='cleared')

    result = runner.invoke(
        cli,
        ['clusters', 'clear-indices', '--cluster', 'source', '--acknowledge-risk'],
        catch_exceptions=True,
    )

    assert result.exit_code == 0
    clear_indices.assert_called_once()
    assert clear_indices.call_args.args[0].endpoint == "https://source-a.example.com"


def test_cli_cluster_clear_indices_role_shorthand_error_lists_selector_flag(runner, mocker):
    env = _catalog_env([
        ConsoleResourceEntry(ResourceRole.SOURCE, "source-a", ["source-a"],
                             client_config=_catalog_cluster_config("https://source-a.example.com")),
        ConsoleResourceEntry(ResourceRole.SOURCE, "source-b", ["source-b"],
                             client_config=_catalog_cluster_config("https://source-b.example.com")),
        ConsoleResourceEntry(ResourceRole.TARGET, "target-a", ["target-a"],
                             client_config=_catalog_cluster_config("https://target-a.example.com")),
    ])
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    clear_indices = mocker.patch('console_link.middleware.clusters.clear_indices', return_value='cleared')

    result = runner.invoke(
        cli,
        ['clusters', 'clear-indices', '--cluster', 'source', '--acknowledge-risk'],
        catch_exceptions=True,
    )

    assert result.exit_code == 2
    assert "Multiple source resources are configured" in result.output
    assert "Specify: --source <source-a|source-b>." in result.output
    clear_indices.assert_not_called()


def test_cli_cluster_selector_completion_includes_roles_and_cluster_names(mocker):
    env = _catalog_env([
        ConsoleResourceEntry(ResourceRole.SOURCE, "source1", ["source1"],
                             client_config=_catalog_cluster_config("https://source1.example.com")),
        ConsoleResourceEntry(ResourceRole.TARGET, "target1", ["target1"],
                             client_config=_catalog_cluster_config("https://target1.example.com")),
        ConsoleResourceEntry(ResourceRole.PROXY, "proxy1", ["proxy1", "captureproxy.proxy1"],
                             client_config=_catalog_cluster_config("https://proxy1.example.com")),
    ])
    ctx = mocker.Mock()
    ctx.find_root.return_value.params = {}
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)

    completions = cli_module.get_cluster_resource_completions(ctx, None, "s")

    assert [item.value for item in completions] == ["source", "source1"]


def test_cli_cluster_selector_completion_omits_ambiguous_roles_and_k8s_aliases(mocker):
    env = _catalog_env([
        ConsoleResourceEntry(ResourceRole.SOURCE, "source-a", ["source-a"],
                             client_config=_catalog_cluster_config("https://source-a.example.com")),
        ConsoleResourceEntry(ResourceRole.SOURCE, "source-b", ["source-b"],
                             client_config=_catalog_cluster_config("https://source-b.example.com")),
        ConsoleResourceEntry(ResourceRole.TARGET, "targeta", ["targeta"],
                             client_config=_catalog_cluster_config("https://targeta.example.com")),
        ConsoleResourceEntry(ResourceRole.TARGET, "targetb", ["targetb"],
                             client_config=_catalog_cluster_config("https://targetb.example.com")),
        ConsoleResourceEntry(ResourceRole.PROXY, "proxy-a", ["proxy-a", "captureproxy.proxy-a"],
                             k8s_name="proxy-a",
                             client_config=_catalog_cluster_config("https://proxy-a.example.com")),
        ConsoleResourceEntry(ResourceRole.PROXY, "proxy-b", ["proxy-b", "captureproxy.proxy-b"],
                             k8s_name="proxy-b",
                             client_config=_catalog_cluster_config("https://proxy-b.example.com")),
    ])
    ctx = mocker.Mock()
    ctx.find_root.return_value.params = {}
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)

    completions = cli_module.get_cluster_resource_completions(ctx, None, "")

    assert [item.value for item in completions] == [
        "proxy-a",
        "proxy-b",
        "source-a",
        "source-b",
        "targeta",
        "targetb",
    ]


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


def test_cli_cluster_connection_check_proxy(runner, mocker, proxy_enabled_yaml_path):
    middleware_mock = mocker.spy(middleware.clusters, 'connection_check')
    api_mock = mocker.patch.object(Cluster, 'call_api')
    result = runner.invoke(cli, ['--config-file', str(proxy_enabled_yaml_path), 'clusters', 'connection-check',
                                 '--cluster', 'proxy'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    middleware_mock.assert_called_once()
    api_mock.assert_called_once()


def test_cli_version_check(runner, mocker):
    result = runner.invoke(cli, ["--version"], catch_exceptions=True)
    assert result.exit_code == 0
    assert "Migration Assistant" in result.output


def test_missing_command(runner, mocker):
    result = runner.invoke(cli, [], catch_exceptions=True)
    assert result.exit_code == 2
    assert "Error: Missing command" in result.output


def test_cli_cluster_cat_indices_and_connection_check_with_one_cluster(runner, mocker,
                                                                       target_cluster_only_yaml_path,
                                                                       source_cluster_only_yaml_path):
    middleware_connection_check_mock = mocker.spy(middleware.clusters, 'connection_check')
    middleware_cat_indices_mock = mocker.spy(middleware.clusters, 'cat_indices')
    api_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    # Connection check defaults to source+target and requires both roles.
    result = runner.invoke(cli, ['--config-file', str(source_cluster_only_yaml_path), 'clusters', 'connection-check'],
                           catch_exceptions=True)
    assert result.exit_code == 2
    assert "No target resource is configured" in result.output
    middleware_connection_check_mock.assert_not_called()
    api_mock.assert_not_called()

    # Single-cluster mode only requires the selected role.
    result = runner.invoke(cli, ['--config-file', str(source_cluster_only_yaml_path), 'clusters', 'connection-check',
                                 '--cluster', 'source'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    middleware_connection_check_mock.assert_called_once()
    api_mock.assert_called_once()
    middleware_connection_check_mock.reset_mock()
    api_mock.reset_mock()

    result = runner.invoke(cli, ['--config-file', str(target_cluster_only_yaml_path), 'clusters', 'connection-check'],
                           catch_exceptions=True)
    assert result.exit_code == 2
    assert "No source resource is configured" in result.output
    middleware_connection_check_mock.assert_not_called()
    api_mock.assert_not_called()

    result = runner.invoke(cli, ['--config-file', str(target_cluster_only_yaml_path), 'clusters', 'connection-check',
                                 '--cluster', 'target'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    middleware_connection_check_mock.assert_called_once()
    api_mock.assert_called_once()
    middleware_connection_check_mock.reset_mock()
    api_mock.reset_mock()

    result = runner.invoke(cli, ['--config-file', str(source_cluster_only_yaml_path), 'clusters', 'cat-indices'],
                           catch_exceptions=True)
    assert result.exit_code == 2
    assert "No target resource is configured" in result.output
    middleware_cat_indices_mock.assert_not_called()
    api_mock.assert_not_called()

    result = runner.invoke(cli, ['--config-file', str(source_cluster_only_yaml_path), 'clusters', 'cat-indices',
                                 '--cluster', 'source'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    middleware_cat_indices_mock.assert_called_once()
    api_mock.assert_called_once()
    middleware_cat_indices_mock.reset_mock()
    api_mock.reset_mock()

    result = runner.invoke(cli, ['--config-file', str(target_cluster_only_yaml_path), 'clusters', 'cat-indices'],
                           catch_exceptions=True)
    assert result.exit_code == 2
    assert "No source resource is configured" in result.output
    middleware_cat_indices_mock.assert_not_called()
    api_mock.assert_not_called()

    result = runner.invoke(cli, ['--config-file', str(target_cluster_only_yaml_path), 'clusters', 'cat-indices',
                                 '--cluster', 'target'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    middleware_cat_indices_mock.assert_called_once()
    api_mock.assert_called_once()


def test_cli_cluster_run_test_benchmarks(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'run_test_benchmarks')
    model_mock = mocker.patch.object(Cluster, 'execute_benchmark_workload')
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'run-test-benchmarks',
                                 '--cluster', 'source'],
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


def test_cli_cluster_run_test_benchmarks_proxy(runner, mocker, proxy_enabled_yaml_path):
    middleware_mock = mocker.spy(middleware.clusters, 'run_test_benchmarks')
    model_mock = mocker.patch.object(Cluster, 'execute_benchmark_workload')
    result = runner.invoke(cli, ['--config-file', str(proxy_enabled_yaml_path), 'clusters', 'run-test-benchmarks',
                                 '--cluster', 'proxy'],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called()
    assert result.exit_code == 0


def test_cli_cluster_run_curl_source_cluster(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    json_body = '{"id": 3, "number": 5}'
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'source', 'new_index/_doc', '-XPOST', '--json', json_body],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called_once()
    assert model_mock.call_args.kwargs == {'path': '/new_index/_doc', 'method': HttpMethod.POST,
                                           'data': '{"id": 3, "number": 5}',
                                           'headers': {'Content-Type': 'application/json'},
                                           'timeout': 15, 'session': None, 'raise_error': False}
    assert result.exit_code == 0


def test_cli_cluster_run_curl_target_cluster(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'target', '/_cat/indices', '-XGET', '-H', 'user-agent:TestAgent'],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called_once()
    assert model_mock.call_args.kwargs == {'path': '/_cat/indices', 'method': HttpMethod.GET,
                                           'data': None, 'headers': {'user-agent': 'TestAgent'}, 'timeout': 15,
                                           'session': None, 'raise_error': False}
    assert result.exit_code == 0


def test_cli_cluster_run_curl_proxy(runner, mocker, proxy_enabled_yaml_path):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(proxy_enabled_yaml_path), 'clusters', 'curl',
                                 'proxy', '/_cat/indices', '-XGET'],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called_once()
    assert model_mock.call_args.kwargs == {'path': '/_cat/indices', 'method': HttpMethod.GET,
                                           'data': None, 'headers': {}, 'timeout': 15,
                                           'session': None, 'raise_error': False}
    assert result.exit_code == 0


def test_cli_cluster_run_curl_undefined_cluster(runner, mocker, source_cluster_only_yaml_path):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(source_cluster_only_yaml_path), 'clusters', 'curl',
                                 'target', '/_cat/indices'],
                           catch_exceptions=True)
    middleware_mock.assert_not_called()
    model_mock.assert_not_called()
    assert result.exit_code == 2


def test_cli_cluster_run_curl_bad_json(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    malformed_json = '{"id": 3, "number": "5}'
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'source', '/new-index', '-XPOST', '--json', malformed_json],
                           catch_exceptions=True)
    middleware_mock.assert_not_called()
    model_mock.assert_not_called()
    assert result.exit_code == 2


def test_cli_cluster_run_curl_bad_headers(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'source', '/new-index', '-H', 'key=value'],
                           catch_exceptions=True)
    middleware_mock.assert_not_called()
    model_mock.assert_not_called()
    assert result.exit_code == 2


def test_cli_cluster_run_curl_multiple_headers(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    headers = [('key1', 'value1'), ('key2', 'value2')]
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'target', '/', '-H', f"{headers[0][0]}:{headers[0][1]}",
                                 "-H", f"{headers[1][0]}:{headers[1][1]}"],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called_once()
    assert model_mock.call_args.kwargs == {'path': '/', 'method': HttpMethod.GET,
                                           'data': None, 'headers': {k: v for k, v in headers}, 'timeout': 15,
                                           'session': None, 'raise_error': False}
    assert result.exit_code == 0


def test_cli_cluster_run_curl_head_method(runner, mocker):
    middleware_mock = mocker.spy(middleware.clusters, 'call_api')
    model_mock = mocker.patch.object(Cluster, 'call_api', autospec=True)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'target', '/', '-X', 'HEAD'],
                           catch_exceptions=True)
    middleware_mock.assert_called_once()
    model_mock.assert_called_once()
    assert model_mock.call_args.kwargs == {'path': '/', 'method': HttpMethod.HEAD,
                                           'data': None, 'headers': {}, 'timeout': 15,
                                           'session': None, 'raise_error': False}
    assert result.exit_code == 0


def test_cli_cluster_curl_timeout_shows_friendly_error(runner, mocker):
    import requests.exceptions
    mocker.patch.object(Cluster, 'call_api', autospec=True,
                        side_effect=requests.exceptions.ReadTimeout(
                            "HTTPSConnectionPool(host='example.com', port=443): Read timed out. (read timeout=15)"))
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'clusters', 'curl',
                                 'target', '/_cluster/health'],
                           catch_exceptions=True)
    assert result.exit_code == 0
    assert "timed out" in result.output
    assert "--timeout" in result.output
    assert "HTTPSConnectionPool" not in result.output
    assert "Traceback" not in result.output


def test_cli_cluster_generate_data_proxy(runner, mocker, proxy_enabled_yaml_path):
    module_mock = mocker.patch('importlib.util.module_from_spec')
    spec_mock = mocker.patch('importlib.util.spec_from_file_location')
    exists_mock = mocker.patch('os.path.exists', return_value=True)
    bulk_insert_data = mocker.Mock(return_value={
        'total_inserted': 10,
        'total_errors': 0,
        'elapsed_time': 1.0,
        'docs_per_sec': 10.0,
        'estimated_size_mb': 0.1
    })
    module_mock.return_value.bulk_insert_data = bulk_insert_data
    spec_mock.return_value.loader.exec_module = mocker.Mock()

    result = runner.invoke(
        cli,
        ['--config-file', str(proxy_enabled_yaml_path), 'clusters', 'generate-data',
         '--cluster', 'proxy', '--index-name', 'test-index', '--num-docs', '10'],
        catch_exceptions=True
    )

    exists_mock.assert_called()
    bulk_insert_data.assert_called_once()
    assert result.exit_code == 0


def test_cli_cluster_generate_data_partial_insert_exits_nonzero(runner, mocker, proxy_enabled_yaml_path):
    # Regression test: when bulk_insert_data bails early after persistent failures (e.g.
    # sigv4-via-proxy 403s on the AOSS test pipeline), the CLI must exit non-zero so the
    # integ test sees a clear error in <30s instead of waiting for an outer subprocess
    # timeout to fire 5 minutes later.
    mocker.patch('importlib.util.module_from_spec').return_value.bulk_insert_data = mocker.Mock(
        return_value={
            'total_inserted': 0,
            'total_errors': 50,
            'elapsed_time': 1.0,
            'docs_per_sec': 0.0,
            'estimated_size_mb': 0.0,
        }
    )
    mocker.patch('importlib.util.spec_from_file_location').return_value.loader.exec_module = mocker.Mock()
    mocker.patch('os.path.exists', return_value=True)

    result = runner.invoke(
        cli,
        ['--config-file', str(proxy_enabled_yaml_path), 'clusters', 'generate-data',
         '--cluster', 'proxy', '--index-name', 'test-index', '--num-docs', '50'],
        catch_exceptions=True,
    )

    assert result.exit_code != 0
    assert 'Inserted 0 of 50' in result.output


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
    mock = mocker.patch.object(FileSystemSnapshot, 'delete', autospec=True)

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
    mock_ecs_service_call = mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True,
                                                return_value=mocked_running_status)
    mock_detailed_status_call = mocker.patch('console_link.models.backfill_rfs.get_detailed_status', autospec=True,
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


def test_get_backfill_status_with_deep_check_as_json(runner, mocker):
    mocked_status = {
        "status": "Completed",
        "percentage_completed": 100.0,
        "eta_ms": None,
        "started": "2026-04-19T21:40:01+00:00",
        "finished": "2026-04-19T21:40:01+00:00",
        "shard_total": 0,
        "shard_complete": 0,
        "shard_in_progress": 0,
        "shard_waiting": 0,
    }
    mock_build_status = mocker.patch.object(
        ECSRFSBackfill,
        'build_backfill_status',
        autospec=True,
        return_value=cli_module.BackfillOverallStatus(**mocked_status)
    )
    mock_middleware_status = mocker.patch('console_link.middleware.backfill.status')

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         '--json', 'backfill', 'status', '--deep-check'],
        catch_exceptions=True
    )

    assert result.exit_code == 0
    assert json.loads(result.output) == mocked_status
    mock_build_status.assert_called_once()
    mock_middleware_status.assert_not_called()


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
    assert "No kafka resource is configured" in result.output


def test_cli_kafka_create_topic(runner, mocker):
    middleware_mock = mocker.spy(middleware.kafka, 'create_topic')
    model_mock = mocker.patch.object(StandardKafka, 'create_topic')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'create-topic',
                                 'test'],
                           catch_exceptions=True)

    model_mock.assert_called_once_with(topic_name='test')
    middleware_mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_kafka_list_topics(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'list_topics')
    middleware_mock = mocker.spy(middleware.kafka, 'list_topics')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'list-topics'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with()
    middleware_mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_kafka_list_topics_uses_selected_k8s_resource(runner, mocker):
    env = _catalog_env([
        ConsoleResourceEntry(
            ResourceRole.KAFKA,
            "kafka-a",
            ["kafka-a"],
            kafka_runtime={
                "type": "direct",
                "clientConfig": {"broker_endpoints": "broker-a:9092", "standard": None},
            },
        ),
        ConsoleResourceEntry(
            ResourceRole.KAFKA,
            "kafka-b",
            ["kafka-b"],
            kafka_runtime={
                "type": "direct",
                "clientConfig": {"broker_endpoints": "broker-b:9092", "standard": None},
            },
        ),
    ])
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    list_topics = mocker.patch('console_link.middleware.kafka.list_topics',
                               return_value=CommandResult(success=True, value='topic-a\n'))

    result = runner.invoke(cli, ['kafka', 'list-topics', '--kafka', 'kafka-b'], catch_exceptions=True)

    assert result.exit_code == 0
    assert list_topics.call_args.args[0].brokers == "broker-b:9092"


def test_cli_kafka_selector_completion_omits_k8s_aliases(mocker):
    env = _catalog_env([
        ConsoleResourceEntry(
            ResourceRole.KAFKA,
            "default",
            ["default", "kafkacluster.default"],
            k8s_name="default",
            kafka_runtime={
                "type": "direct",
                "clientConfig": {"broker_endpoints": "broker-a:9092", "standard": None},
            },
        ),
        ConsoleResourceEntry(
            ResourceRole.KAFKA,
            "kafka-b",
            ["kafka-b", "kafkacluster.kafka-b"],
            k8s_name="kafka-b",
            kafka_runtime={
                "type": "direct",
                "clientConfig": {"broker_endpoints": "broker-b:9092", "standard": None},
            },
        ),
    ])
    ctx = mocker.Mock()
    ctx.find_root.return_value.params = {}
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)

    completions = cli_module.get_kafka_resource_completions(ctx, None, "")

    assert [item.value for item in completions] == ["default", "kafka-b"]


def test_cli_kafka_list_topics_requires_selector_for_ambiguous_k8s_resources(runner, mocker):
    env = _catalog_env([
        ConsoleResourceEntry(
            ResourceRole.KAFKA,
            "kafka-a",
            ["kafka-a"],
            kafka_runtime={
                "type": "direct",
                "clientConfig": {"broker_endpoints": "broker-a:9092", "standard": None},
            },
        ),
        ConsoleResourceEntry(
            ResourceRole.KAFKA,
            "kafka-b",
            ["kafka-b"],
            kafka_runtime={
                "type": "direct",
                "clientConfig": {"broker_endpoints": "broker-b:9092", "standard": None},
            },
        ),
    ])
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    list_topics = mocker.patch('console_link.middleware.kafka.list_topics',
                               return_value=CommandResult(success=True, value='topic-a\n'))

    result = runner.invoke(cli, ['kafka', 'list-topics'], catch_exceptions=True)

    assert result.exit_code == 2
    assert "Multiple kafka resources are configured" in result.output
    assert "Specify: --kafka <kafka-a|kafka-b>." in result.output
    list_topics.assert_not_called()


def test_cli_kafka_group_errors_when_no_kafka_resource_is_configured(runner, mocker):
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=_catalog_env([]))

    result = runner.invoke(cli, ['kafka', 'list-topics'], catch_exceptions=True)

    assert result.exit_code == 2
    assert "No kafka resource is configured" in result.output


def test_cli_kafka_delete_topic(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'delete_topic')
    middleware_mock = mocker.spy(middleware.kafka, 'delete_topic')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'delete-topic',
                                 '--acknowledge-risk', 'test'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(topic_name='test')
    middleware_mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_kafka_describe_consumer_group(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'describe_consumer_group')
    middleware_mock = mocker.spy(middleware.kafka, 'describe_consumer_group')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'describe-consumer-group',
                                 'test-group'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(group_name='test-group')
    middleware_mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_kafka_describe_consumer_group_falls_back_to_legacy_default(runner, mocker):
    # No workflow-resolved groups => use the legacy default name. This keeps
    # CDK/docker-compose deployments and the local dev path working unchanged.
    model_mock = mocker.patch.object(StandardKafka, 'describe_consumer_group')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'describe-consumer-group'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(group_name='logging-group-default')
    assert result.exit_code == 0


def test_cli_kafka_describe_consumer_group_uses_resolved_group_when_unambiguous(runner, mocker):
    # When the env was built from a workflow config containing replayers, the
    # CLI default should be the workflow-resolved group, not the legacy name.
    model_mock = mocker.patch.object(StandardKafka, 'describe_consumer_group')

    real_env = Environment(config_file=VALID_SERVICES_YAML)
    real_env.kafka_consumer_groups = ["replayer-prod-target"]

    class _StubContext:
        env = real_env

    mocker.patch.object(cli_module, "Context", return_value=_StubContext())

    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'describe-consumer-group'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(group_name='replayer-prod-target')
    assert result.exit_code == 0


def test_cli_kafka_describe_consumer_group_requires_group_when_legacy_groups_are_ambiguous(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'describe_consumer_group')

    real_env = Environment(config_file=VALID_SERVICES_YAML)
    real_env.kafka_consumer_groups = ["replayer-prod-target", "replayer-staging-target"]

    class _StubContext:
        env = real_env

    mocker.patch.object(cli_module, "Context", return_value=_StubContext())

    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'describe-consumer-group'],
                           catch_exceptions=True)

    assert result.exit_code == 2
    assert "Multiple consumer groups are configured: replayer-prod-target, replayer-staging-target" in result.output
    assert "Specify: GROUP_NAME <replayer-prod-target|replayer-staging-target>." in result.output
    model_mock.assert_not_called()


def test_cli_kafka_describe_consumer_group_uses_only_group_for_selected_k8s_kafka(runner, mocker):
    env = _catalog_env(
        [
            _catalog_kafka_entry("default", "broker-a:9092"),
            _catalog_kafka_entry("kafka-b", "broker-b:9092"),
        ],
        consumer_groups=[
            ConsoleConsumerGroupEntry(
                name="replayer-targetb",
                kafka_ref="kafka-b",
                target_ref="targetb",
                replay_ref="proxy-b-targetb",
            ),
        ],
    )
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    model_mock = mocker.patch.object(StandardKafka, 'describe_consumer_group')

    result = runner.invoke(cli, ['kafka', 'describe-consumer-group', '--kafka', 'kafka-b'],
                           catch_exceptions=True)

    assert result.exit_code == 0
    model_mock.assert_called_once_with(group_name='replayer-targetb')


def test_cli_kafka_describe_consumer_group_requires_group_when_selected_kafka_has_multiple_groups(runner, mocker):
    env = _catalog_env(
        [
            _catalog_kafka_entry("default", "broker-a:9092"),
            _catalog_kafka_entry("kafka-b", "broker-b:9092"),
        ],
        consumer_groups=[
            ConsoleConsumerGroupEntry(
                name="replayer-targeta",
                kafka_ref="default",
                target_ref="targeta",
                replay_ref="proxy-a-targeta",
            ),
            ConsoleConsumerGroupEntry(
                name="replayer-targetb",
                kafka_ref="default",
                target_ref="targetb",
                replay_ref="proxy-b-targetb",
            ),
        ],
    )
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    model_mock = mocker.patch.object(StandardKafka, 'describe_consumer_group')

    result = runner.invoke(cli, ['kafka', 'describe-consumer-group', '--kafka', 'default'],
                           catch_exceptions=True)

    assert result.exit_code == 2
    assert "Multiple consumer groups are configured for kafka resource 'default'" in result.output
    assert "Specify: GROUP_NAME <replayer-targeta|replayer-targetb>." in result.output
    model_mock.assert_not_called()


def test_cli_kafka_describe_consumer_group_does_not_use_group_from_another_kafka(runner, mocker):
    env = _catalog_env(
        [
            _catalog_kafka_entry("default", "broker-a:9092"),
            _catalog_kafka_entry("kafka-b", "broker-b:9092"),
        ],
        consumer_groups=[
            ConsoleConsumerGroupEntry(
                name="replayer-targeta",
                kafka_ref="default",
                target_ref="targeta",
                replay_ref="proxy-a-targeta",
            ),
        ],
    )
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    model_mock = mocker.patch.object(StandardKafka, 'describe_consumer_group')

    result = runner.invoke(cli, ['kafka', 'describe-consumer-group', '--kafka', 'kafka-b'],
                           catch_exceptions=True)

    assert result.exit_code == 2
    assert "No consumer groups are configured for kafka resource 'kafka-b'" in result.output
    assert "`console kafka list-consumer-groups --kafka kafka-b`" in result.output
    model_mock.assert_not_called()


def test_cli_kafka_list_consumer_groups(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'list_consumer_groups')
    middleware_mock = mocker.spy(middleware.kafka, 'list_consumer_groups')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'list-consumer-groups'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with()
    middleware_mock.assert_called_once()
    assert result.exit_code == 0


def test_kafka_topic_completion_uses_list_topics(mocker, env):
    ctx = mocker.Mock()
    ctx.find_root.return_value.params = {'config_file': '/fake/config.yaml', 'force_use_config_file': True}
    mocker.patch('console_link.cli.Environment', return_value=env)
    topic_output = '__consumer_offsets\nlogging-traffic-topic\nlogs-topic\n'
    mocker.patch('console_link.middleware.kafka.list_topics',
                 return_value=CommandResult(success=True, value=topic_output))

    completions = get_kafka_topic_completions(ctx, None, 'log')

    assert [item.value for item in completions] == ['logging-traffic-topic', 'logs-topic']


def test_kafka_consumer_group_completion_uses_list_groups(mocker, env):
    ctx = mocker.Mock()
    ctx.find_root.return_value.params = {'config_file': '/fake/config.yaml', 'force_use_config_file': True}
    mocker.patch('console_link.cli.Environment', return_value=env)
    mocker.patch('console_link.middleware.kafka.list_consumer_groups',
                 return_value=CommandResult(success=True, value='logging-group-default\nother-group\n'))

    completions = get_kafka_consumer_group_completions(ctx, None, 'log')

    assert [item.value for item in completions] == ['logging-group-default']


def test_kafka_consumer_group_completion_omits_empty_list_success_message(mocker, env):
    ctx = mocker.Mock()
    ctx.find_root.return_value.params = {'config_file': '/fake/config.yaml', 'force_use_config_file': True}
    mocker.patch('console_link.cli.Environment', return_value=env)
    mocker.patch(
        'console_link.middleware.kafka.list_consumer_groups',
        return_value=CommandResult(
            success=True,
            value='Command for List Consumer Groups completed successfully.\n',
        ),
    )

    completions = get_kafka_consumer_group_completions(ctx, None, '')

    assert completions == []


def test_cli_kafka_describe_topic(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'describe_topic_records')
    middleware_mock = mocker.spy(middleware.kafka, 'describe_topic_records')
    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'describe-topic-records',
                                 'test'],
                           catch_exceptions=True)
    model_mock.assert_called_once_with(topic_name='test')
    middleware_mock.assert_called_once()
    assert result.exit_code == 0


def test_cli_kafka_describe_topic_records_falls_back_to_legacy_default(runner, mocker):
    model_mock = mocker.patch.object(StandardKafka, 'describe_topic_records')

    result = runner.invoke(cli, ['-vv', '--config-file', str(VALID_SERVICES_YAML), 'kafka', 'describe-topic-records'],
                           catch_exceptions=True)

    model_mock.assert_called_once_with(topic_name='logging-traffic-topic')
    assert result.exit_code == 0


def test_cli_kafka_describe_topic_records_uses_only_topic_for_selected_k8s_kafka(runner, mocker):
    env = _catalog_env([
        _catalog_kafka_entry("default", "broker-a:9092"),
    ])
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    mocker.patch(
        'console_link.middleware.kafka.list_topics',
        return_value=CommandResult(success=True, value='__consumer_offsets\nproxy-a-traffic\n'),
    )
    describe_records = mocker.patch(
        'console_link.middleware.kafka.describe_topic_records',
        return_value=CommandResult(success=True, value='TOPIC PARTITION RECORDS\n'),
    )

    result = runner.invoke(cli, ['kafka', 'describe-topic-records', '--kafka', 'default'],
                           catch_exceptions=True)

    assert result.exit_code == 0
    describe_records.assert_called_once()
    assert describe_records.call_args.kwargs["topic_name"] == "proxy-a-traffic"


def test_cli_kafka_describe_topic_records_requires_topic_when_selected_kafka_has_multiple_topics(runner, mocker):
    env = _catalog_env([
        _catalog_kafka_entry("default", "broker-a:9092"),
    ])
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    mocker.patch(
        'console_link.middleware.kafka.list_topics',
        return_value=CommandResult(success=True, value='__consumer_offsets\nproxy-a-traffic\nproxy-b-traffic\n'),
    )
    describe_records = mocker.patch('console_link.middleware.kafka.describe_topic_records')

    result = runner.invoke(cli, ['kafka', 'describe-topic-records', '--kafka', 'default'],
                           catch_exceptions=True)

    assert result.exit_code == 2
    assert "Multiple topics are available for kafka resource 'default'" in result.output
    assert "Specify: TOPIC_NAME <proxy-a-traffic|proxy-b-traffic>." in result.output
    describe_records.assert_not_called()


def test_cli_kafka_describe_topic_records_does_not_default_to_internal_topic(runner, mocker):
    env = _catalog_env([
        _catalog_kafka_entry("kafka-b", "broker-b:9092"),
    ])
    mocker.patch.object(cli_module, "can_use_k8s_config_store", return_value=True)
    mocker.patch.object(cli_module.Environment, "from_k8s_resource_catalog", return_value=env)
    mocker.patch(
        'console_link.middleware.kafka.list_topics',
        return_value=CommandResult(success=True, value='__consumer_offsets\n'),
    )
    describe_records = mocker.patch('console_link.middleware.kafka.describe_topic_records')

    result = runner.invoke(cli, ['kafka', 'describe-topic-records', '--kafka', 'kafka-b'],
                           catch_exceptions=True)

    assert result.exit_code == 2
    assert "No non-internal topics are available for kafka resource 'kafka-b'" in result.output
    assert "`console kafka list-topics --kafka kafka-b`" in result.output
    describe_records.assert_not_called()


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


# Tests for exception handling functionality

class CustomTestException(Exception):
    """Custom exception for testing exception handling"""
    pass


def _run_exception_test(log_level, exception_message="Test error message",
                        handler_func=None, expected_outputs=None, unexpected_outputs=None):
    import io
    import sys

    # Create a simple mock function to simulate the behavior of cli when it raises an exception
    def mock_main():
        raise CustomTestException(exception_message)

    # Default handler for normal mode
    if handler_func is None:
        def exception_handler(exc):
            print(f"Error: {str(exc)}", file=sys.stderr)
            return 1

    # Default expected/unexpected outputs
    if expected_outputs is None:
        expected_outputs = []
    if unexpected_outputs is None:
        unexpected_outputs = []

    # Capture stdout and stderr
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    captured_output = io.StringIO()
    sys.stdout = sys.stderr = captured_output

    # Set logging level
    root_logger = logging.getLogger()
    original_level = root_logger.getEffectiveLevel()
    root_logger.setLevel(log_level)

    try:
        # Execute the function that simulates main with exception
        exit_code = 0
        try:
            mock_main()
        except Exception as exc:
            exit_code = exception_handler(exc) if handler_func is None else handler_func(exc)
    finally:
        # Restore stdout, stderr and logging
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        root_logger.setLevel(original_level)

    # Get the output
    output = captured_output.getvalue()

    # Run assertions if provided
    for expected in expected_outputs:
        assert expected in output

    for unexpected in unexpected_outputs:
        assert unexpected not in output

    return output, exit_code


def test_main_exception_handling_normal_mode(runner, mocker):
    """Test that main() shows clean error messages in normal mode"""
    output, exit_code = _run_exception_test(
        log_level=logging.WARN,
        expected_outputs=["Error: Test error message"],
        unexpected_outputs=["Traceback"]
    )
    assert exit_code == 1


def test_main_exception_handling_verbose_mode(runner, mocker):
    """Test that main() shows full traceback in verbose mode"""
    import sys

    def verbose_handler(exc):
        import traceback
        print("Error occurred with verbose mode enabled, showing full traceback:", file=sys.stderr)
        print(traceback.format_exc(), file=sys.stderr)
        return 1

    output, exit_code = _run_exception_test(
        log_level=logging.INFO,
        handler_func=verbose_handler,
        expected_outputs=[
            "Error occurred with verbose mode enabled, showing full traceback:",
            "CustomTestException: Test error message",
            "Traceback"
        ]
    )
    assert exit_code == 1


def test_main_exception_handling_debug_mode(runner, mocker):
    """Test that main() shows full traceback in debug mode (even more verbose)"""
    import sys

    def verbose_handler(exc):
        import traceback
        print("Error occurred with verbose mode enabled, showing full traceback:", file=sys.stderr)
        print(traceback.format_exc(), file=sys.stderr)
        return 1

    output, exit_code = _run_exception_test(
        log_level=logging.DEBUG,
        handler_func=verbose_handler,
        expected_outputs=[
            "Error occurred with verbose mode enabled, showing full traceback:",
            "CustomTestException: Test error message",
            "Traceback"
        ]
    )
    assert exit_code == 1


def test_main_exception_handling_warn_level_shows_clean_message(runner, mocker):
    """Test that main() shows clean messages when logging is at WARN level"""
    output, exit_code = _run_exception_test(
        log_level=logging.WARN,
        expected_outputs=["Error: Test error message"],
        unexpected_outputs=["Traceback", "Error occurred with verbose mode enabled"]
    )
    assert exit_code == 1


def _run_cli_integration_test(mocker, log_level, exception_message="Connection failed",
                              expected_outputs=None, unexpected_outputs=None):
    """Helper function to run CLI integration tests"""
    import io
    import sys

    # Mock the cli function
    mock_cli = mocker.patch('console_link.cli.cli')
    mock_cli.side_effect = CustomTestException(exception_message)

    # Default expected/unexpected outputs
    if expected_outputs is None:
        expected_outputs = []
    if unexpected_outputs is None:
        unexpected_outputs = []

    # Capture stdout and stderr
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    captured_output = io.StringIO()
    sys.stdout = sys.stderr = captured_output

    # Set logging level
    root_logger = logging.getLogger()
    original_level = root_logger.getEffectiveLevel()
    root_logger.setLevel(log_level)

    try:
        # Execute main with exception
        exit_code = 0
        try:
            main()
        except SystemExit as exc:
            exit_code = exc.code
    finally:
        # Restore stdout, stderr and logging
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        root_logger.setLevel(original_level)

    # Get the output
    output = captured_output.getvalue()

    # Run assertions if provided
    for expected in expected_outputs:
        assert expected in output

    for unexpected in unexpected_outputs:
        assert unexpected not in output

    # Verify mock was called
    mock_cli.assert_called()

    return output, exit_code, mock_cli


def test_cli_integration_with_exception_normal_mode(runner, mocker):
    """Test CLI integration where an actual CLI command raises an exception in normal mode"""
    output, exit_code, mock_cli = _run_cli_integration_test(
        mocker=mocker,
        log_level=logging.WARN,
        expected_outputs=["Error: Connection failed"],
        unexpected_outputs=["Traceback"]
    )
    assert exit_code == 1


def test_cli_integration_with_exception_verbose_mode(runner, mocker):
    """Test CLI integration where an actual CLI command raises an exception in verbose mode"""
    output, exit_code, mock_cli = _run_cli_integration_test(
        mocker=mocker,
        log_level=logging.INFO,
        expected_outputs=[
            "Error occurred with verbose mode enabled, showing full traceback:",
            "CustomTestException: Connection failed",
            "Traceback"
        ]
    )
    assert exit_code == 1


# ##################### failed document stream CLI commands ###################
#
# These cover the failed_document_stream subgroup (location/count/list) and the related
# additions to backfill (the new `reset` command + failed document stream summary appended
# to `backfill status`). Coverage gap was introduced by the failed document stream branch.

# Bare-minimum services file: just a target cluster is enough for the failed_document_stream
# group, since none of the failed_document_stream commands touch the configured backfill.
TARGET_ONLY_SERVICES_YAML = TEST_DATA_DIRECTORY / "services.yaml"


def _fake_failed_document_stream_cfg(cli_module_=None):
    """Construct a real FailedDocumentStreamConfig (frozen dataclass) for stubbing load_config.
    Going through the actual class keeps the tests honest if its shape
    changes."""
    from console_link.middleware.failed_document_stream import FailedDocumentStreamConfig
    return FailedDocumentStreamConfig(bucket="b", prefix="rfs-failed-document-stream/",
                                      session_id="sess-A", region=None)


def test_failed_document_stream_location_prints_session_uri(runner, mocker):
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'failed-document-stream', 'location'],
                           catch_exceptions=False)
    assert result.exit_code == 0
    assert "s3://b/rfs-failed-document-stream/session=sess-A/" in result.output


def test_failed_document_stream_location_passes_migration_override(runner, mocker):
    # --migration is the operator's way to choose which SnapshotMigration's stream to inspect
    # when several exist (or to target a specific historical backfill).
    load_mock = mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                                    return_value=_fake_failed_document_stream_cfg())
    result = runner.invoke(
        cli,
        ['--config-file', str(VALID_SERVICES_YAML), 'failed-document-stream', 'location', '--migration', 'backfill-1'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    load_mock.assert_called_once_with(migration_override='backfill-1')


def test_failed_document_stream_location_when_not_configured_raises_click_exception(runner, mocker):
    from console_link.middleware.failed_document_stream import FailedDocumentStreamNotConfigured
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        side_effect=FailedDocumentStreamNotConfigured("no bucket"))
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'failed-document-stream', 'location'],
                           catch_exceptions=False)
    # ClickException exits with code 1 and prints the message via Error: prefix.
    assert result.exit_code == 1
    assert "no bucket" in result.output


def test_failed_document_stream_count_prints_count(runner, mocker):
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    mocker.patch.object(cli_module.failed_document_stream_, "count", return_value=7)
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'failed-document-stream', 'count'],
                           catch_exceptions=False)
    assert result.exit_code == 0
    assert "7" in result.output


def test_failed_document_stream_count_when_not_configured(runner, mocker):
    from console_link.middleware.failed_document_stream import FailedDocumentStreamNotConfigured
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        side_effect=FailedDocumentStreamNotConfigured("no session"))
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'failed-document-stream', 'count'],
                           catch_exceptions=False)
    assert result.exit_code == 1
    assert "no session" in result.output


def test_failed_document_stream_list_text_mode_renders_tab_table(runner, mocker):
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    records = [
        {"timestamp": "2026-05-01T00:00:00Z", "targetIndex": "movies",
         "documentId": "doc-1", "failureClass": "NON_RETRYABLE",
         "failureType": "mapper_parsing_exception"},
        # second record missing some fields — verifies the `r.get(..., '-')`
        # fallback path renders '-' rather than blowing up.
        {"timestamp": "2026-05-02T00:00:00Z", "documentId": "doc-2"},
    ]
    mocker.patch.object(cli_module.failed_document_stream_, "list_records", return_value=records)

    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'failed-document-stream', 'list'],
                           catch_exceptions=False)
    assert result.exit_code == 0
    # Each record on its own line, tab-separated.
    assert "doc-1" in result.output
    assert "NON_RETRYABLE" in result.output
    assert "movies" in result.output
    # The missing fields render as '-'
    assert "doc-2\t-\t-" in result.output


def test_failed_document_stream_list_json_mode_emits_json_array(runner, mocker):
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    records = [{"documentId": "doc-1"}, {"documentId": "doc-2"}]
    mocker.patch.object(cli_module.failed_document_stream_, "list_records", return_value=records)

    result = runner.invoke(
        cli,
        ['--config-file', str(VALID_SERVICES_YAML), '--json', 'failed-document-stream', 'list'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    # The --json path should round-trip cleanly.
    assert json.loads(result.output) == records


def test_failed_document_stream_list_empty_shows_no_records_message(runner, mocker):
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    mocker.patch.object(cli_module.failed_document_stream_, "list_records", return_value=[])

    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'failed-document-stream', 'list'],
                           catch_exceptions=False)
    assert result.exit_code == 0
    assert "no failed document stream records" in result.output


def test_failed_document_stream_list_passes_limit_through_to_middleware(runner, mocker):
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    list_mock = mocker.patch.object(cli_module.failed_document_stream_, "list_records", return_value=[])

    result = runner.invoke(
        cli,
        ['--config-file', str(VALID_SERVICES_YAML), 'failed-document-stream', 'list', '--limit', '5'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    # Confirm Click parsed --limit as int and forwarded it.
    list_mock.assert_called_once()
    assert list_mock.call_args.kwargs == {"limit": 5}


def test_failed_document_stream_list_when_not_configured(runner, mocker):
    from console_link.middleware.failed_document_stream import FailedDocumentStreamNotConfigured
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        side_effect=FailedDocumentStreamNotConfigured("nope"))
    result = runner.invoke(cli, ['--config-file', str(VALID_SERVICES_YAML), 'failed-document-stream', 'list'],
                           catch_exceptions=False)
    assert result.exit_code == 1
    assert "nope" in result.output


# ----- backfill status: failed document stream summary append --------------------------------

def test_backfill_status_appends_failed_document_stream_summary_when_configured(runner, mocker):
    # Stub the underlying ECS lookup so we hit SUCCESS in status_backfill_cmd,
    # then verify the failed document stream tail is appended.
    mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True,
                        return_value=DeploymentStatus(desired=1, running=1, pending=0))
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    mocker.patch.object(cli_module.failed_document_stream_, "safe_count", return_value=4)

    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'status'],
                           catch_exceptions=False)
    assert result.exit_code == 0
    assert "failed document stream location: s3://b/rfs-failed-document-stream/session=sess-A/" in result.output
    assert "Failed document count: 4" in result.output


def test_backfill_status_failed_document_stream_count_unavailable_renders_placeholder(runner, mocker):
    mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True,
                        return_value=DeploymentStatus(desired=1, running=1, pending=0))
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    # safe_count returning None means S3 was unreachable — we mustn't crash.
    mocker.patch.object(cli_module.failed_document_stream_, "safe_count", return_value=None)

    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'status'],
                           catch_exceptions=False)
    assert result.exit_code == 0
    assert "Failed document count: unavailable" in result.output


def test_backfill_status_no_failed_document_stream_section_when_not_configured(runner, mocker):
    mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True,
                        return_value=DeploymentStatus(desired=1, running=1, pending=0))
    from console_link.middleware.failed_document_stream import FailedDocumentStreamNotConfigured
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        side_effect=FailedDocumentStreamNotConfigured("nothing"))

    result = runner.invoke(cli, ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
                                 'backfill', 'status'],
                           catch_exceptions=False)
    assert result.exit_code == 0
    # failed document stream block is intentionally absent when failed document stream isn't configured.
    assert "failed document stream location:" not in result.output
    assert "Failed document count:" not in result.output


def _completed_status_payload():
    return {
        "status": "Completed",
        "percentage_completed": 100.0,
        "eta_ms": None,
        "started": "2026-04-19T21:40:01+00:00",
        "finished": "2026-04-19T21:40:01+00:00",
        "shard_total": 5,
        "shard_complete": 5,
        "shard_in_progress": 0,
        "shard_waiting": 0,
    }


def test_backfill_status_json_deep_check_threads_count_and_includes_keys(runner, mocker):
    # Count threaded into build_backfill_status and surfaced as keys.
    mock_build = mocker.patch.object(ECSRFSBackfill, 'build_backfill_status', autospec=True,
                                     return_value=cli_module.BackfillOverallStatus(**_completed_status_payload()))
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    mocker.patch.object(cli_module.failed_document_stream_, "safe_count", return_value=2)

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         '--json', 'backfill', 'status', '--deep-check'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    assert mock_build.call_args.kwargs["failed_document_count"] == 2
    payload = json.loads(result.output)
    assert payload["failed_document_stream_location"] == "s3://b/rfs-failed-document-stream/session=sess-A/"
    assert payload["failed_document_count"] == 2


def test_backfill_status_json_deep_check_threads_zero_count(runner, mocker):
    mock_build = mocker.patch.object(ECSRFSBackfill, 'build_backfill_status', autospec=True,
                                     return_value=cli_module.BackfillOverallStatus(**_completed_status_payload()))
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    mocker.patch.object(cli_module.failed_document_stream_, "safe_count", return_value=0)

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         '--json', 'backfill', 'status', '--deep-check'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    assert mock_build.call_args.kwargs["failed_document_count"] == 0
    payload = json.loads(result.output)
    assert payload["failed_document_count"] == 0


def test_backfill_status_json_deep_check_threads_unavailable_count(runner, mocker):
    # None => S3 unreachable; passed through as-is.
    mock_build = mocker.patch.object(ECSRFSBackfill, 'build_backfill_status', autospec=True,
                                     return_value=cli_module.BackfillOverallStatus(**_completed_status_payload()))
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    mocker.patch.object(cli_module.failed_document_stream_, "safe_count", return_value=None)

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         '--json', 'backfill', 'status', '--deep-check'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    assert mock_build.call_args.kwargs["failed_document_count"] is None
    payload = json.loads(result.output)
    assert payload["failed_document_count"] is None


def test_backfill_status_json_deep_check_omits_failed_document_stream_keys_when_not_configured(runner, mocker):
    from console_link.middleware.failed_document_stream import FailedDocumentStreamNotConfigured
    mocked_status = cli_module.BackfillOverallStatus(
        status=cli_module.StepStateWithPause.PENDING, percentage_completed=0.0,
    )
    mocker.patch.object(ECSRFSBackfill, 'build_backfill_status', autospec=True,
                        return_value=mocked_status)
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        side_effect=FailedDocumentStreamNotConfigured("missing"))

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         '--json', 'backfill', 'status', '--deep-check'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    payload = json.loads(result.output)
    assert "failed_document_stream_location" not in payload
    assert "failed_document_count" not in payload


def test_backfill_status_json_deep_check_falls_back_to_pending(runner, mocker):
    # When build_backfill_status raises DeepStatusNotYetAvailable, the command
    # should emit a PENDING fallback payload (still augmented with failed document stream keys
    # when configured).
    from console_link.cli import DeepStatusNotYetAvailable
    mocker.patch.object(ECSRFSBackfill, 'build_backfill_status', autospec=True,
                        side_effect=DeepStatusNotYetAvailable("not yet"))
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    mocker.patch.object(cli_module.failed_document_stream_, "safe_count", return_value=0)

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         '--json', 'backfill', 'status', '--deep-check'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    payload = json.loads(result.output)
    assert payload["status"] == "Pending"
    assert payload["percentage_completed"] == 0.0
    assert payload["failed_document_count"] == 0


# ----- backfill reset ------------------------------------------------------

def test_backfill_reset_default_preserves_failed_document_stream(runner, mocker):
    mocker.patch.object(ECSRFSBackfill, 'archive', autospec=True,
                        return_value=CommandResult(success=True, value="/path/to/archive.json"))
    # Without --include-failed-document-stream, the failed_document_stream middleware should never be loaded.
    load_cfg_mock = mocker.patch.object(cli_module.failed_document_stream_, "load_config")
    delete_mock = mocker.patch.object(cli_module.failed_document_stream_, "delete_session")

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         'backfill', 'reset'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    assert "Backfill working state archived" in result.output
    assert "failed document stream records preserved" in result.output
    load_cfg_mock.assert_not_called()
    delete_mock.assert_not_called()


def test_backfill_reset_with_include_failed_document_stream_deletes_session(runner, mocker):
    mocker.patch.object(ECSRFSBackfill, 'archive', autospec=True,
                        return_value=CommandResult(success=True, value="/path/to/archive.json"))
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    delete_mock = mocker.patch.object(cli_module.failed_document_stream_, "delete_session", return_value=3)

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         'backfill', 'reset', '--include-failed-document-stream', '--yes'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    assert "Deleted 3 failed document stream object(s)" in result.output
    delete_mock.assert_called_once()


def test_backfill_reset_include_failed_document_stream_prompts_without_yes(runner, mocker):
    mocker.patch.object(ECSRFSBackfill, 'archive', autospec=True,
                        return_value=CommandResult(success=True, value="/path/to/archive.json"))
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        return_value=_fake_failed_document_stream_cfg())
    delete_mock = mocker.patch.object(cli_module.failed_document_stream_, "delete_session", return_value=0)

    # Send 'n' to abort the click.confirm prompt — verifies the abort path.
    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         'backfill', 'reset', '--include-failed-document-stream'],
        input="n\n",
        catch_exceptions=False,
    )
    # `confirm(..., abort=True)` raises click.Abort -> exit code 1.
    assert result.exit_code == 1
    delete_mock.assert_not_called()


def test_backfill_reset_include_failed_document_stream_when_failed_document_stream_not_configured(runner, mocker):
    from console_link.middleware.failed_document_stream import FailedDocumentStreamNotConfigured
    mocker.patch.object(ECSRFSBackfill, 'archive', autospec=True,
                        return_value=CommandResult(success=True, value="/path/to/archive.json"))
    mocker.patch.object(cli_module.failed_document_stream_, "load_config",
                        side_effect=FailedDocumentStreamNotConfigured("nothing here"))
    delete_mock = mocker.patch.object(cli_module.failed_document_stream_, "delete_session")

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         'backfill', 'reset', '--include-failed-document-stream', '--yes'],
        catch_exceptions=False,
    )
    # No failed document stream configured ⇒ informative message, exit cleanly, no delete call.
    assert result.exit_code == 0
    assert "failed document stream not configured" in result.output
    delete_mock.assert_not_called()


def test_backfill_reset_when_index_doesnt_exist(runner, mocker):
    # WorkingIndexDoesntExist short-circuits the archive flow.
    mocker.patch.object(ECSRFSBackfill, 'archive', autospec=True,
                        return_value=CommandResult(success=False,
                                                   value=WorkingIndexDoesntExist("idx")))

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         'backfill', 'reset'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    assert "Working state index doesn't exist" in result.output


def test_backfill_reset_waits_for_workers_in_progress(runner, mocker):
    # First archive call says "still running", second succeeds — reset_backfill_cmd
    # should poll until the work is done.
    archive_sequence = [
        CommandResult(success=False, value=RfsWorkersInProgress()),
        CommandResult(success=True, value="/path/to/archive.json"),
    ]
    archive_mock = mocker.patch.object(ECSRFSBackfill, 'archive', autospec=True,
                                       side_effect=archive_sequence)
    mocker.patch.object(time, 'sleep', autospec=True)  # don't actually wait 5s

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         'backfill', 'reset'],
        catch_exceptions=False,
    )
    assert result.exit_code == 0
    assert archive_mock.call_count == 2
    assert "RFS Workers are still running" in result.output


def test_backfill_reset_archive_failure_raises(runner, mocker):
    mocker.patch.object(ECSRFSBackfill, 'archive', autospec=True,
                        return_value=CommandResult(success=False, value="archive blew up"))

    result = runner.invoke(
        cli,
        ['--config-file', str(TEST_DATA_DIRECTORY / "services_with_ecs_rfs.yaml"),
         'backfill', 'reset'],
        catch_exceptions=False,
    )
    assert result.exit_code == 1
    assert "archive blew up" in result.output
