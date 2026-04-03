"""Tests for autocomplete_deployments module."""

import json
import time
from unittest.mock import patch, Mock

from console_link.workflow.commands.autocomplete_deployments import (
    _fetch_deployment_names,
    _get_cached_names,
    get_running_deployment_completions,
    get_paused_deployment_completions,
    get_all_deployment_completions,
)
from console_link.workflow.services.deployment_service import DeploymentInfo


def _make_dep(name, replicas, task_name, pre_pause=None):
    return DeploymentInfo(name=name, namespace="ma", replicas=replicas,
                          task_name=task_name, pre_pause_replicas=pre_pause)


RUNNING = _make_dep("rfs", 5, "src.tgt.backfill")
PAUSED = _make_dep("replayer", 0, "src.tgt.replayer", pre_pause=3)


class TestFetchDeploymentNames:
    @patch('console_link.workflow.services.deployment_service.DeploymentService')
    def test_returns_all_names(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING, PAUSED]
        assert _fetch_deployment_names("wf", "ma", "all") == ["src.tgt.backfill", "src.tgt.replayer"]

    @patch('console_link.workflow.services.deployment_service.DeploymentService')
    def test_filters_running(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING, PAUSED]
        assert _fetch_deployment_names("wf", "ma", "running") == ["src.tgt.backfill"]

    @patch('console_link.workflow.services.deployment_service.DeploymentService')
    def test_filters_paused(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING, PAUSED]
        assert _fetch_deployment_names("wf", "ma", "paused") == ["src.tgt.replayer"]

    @patch('console_link.workflow.services.deployment_service.DeploymentService')
    def test_returns_empty_on_exception(self, mock_cls):
        mock_cls.side_effect = Exception("k8s down")
        assert _fetch_deployment_names("wf", "ma") == []


class TestGetCachedNames:
    @patch('console_link.workflow.commands.autocomplete_deployments._fetch_deployment_names')
    def test_fetches_and_caches(self, mock_fetch, tmp_path, monkeypatch):
        monkeypatch.setattr('console_link.workflow.commands.autocomplete_deployments._get_cache_file',
                            lambda wf, kind: tmp_path / f"{kind}_{wf}.json")
        mock_fetch.return_value = ["src.tgt.backfill"]
        ctx = Mock()
        ctx.params = {"workflow_name": "wf", "namespace": "ma"}

        result = _get_cached_names(ctx, "all")
        assert result == ["src.tgt.backfill"]
        assert (tmp_path / "all_wf.json").exists()

    @patch('console_link.workflow.commands.autocomplete_deployments._fetch_deployment_names')
    def test_uses_cache_when_fresh(self, mock_fetch, tmp_path, monkeypatch):
        cache_file = tmp_path / "all_wf.json"
        cache_file.write_text(json.dumps(["cached.task"]))
        monkeypatch.setattr('console_link.workflow.commands.autocomplete_deployments._get_cache_file',
                            lambda wf, kind: cache_file)
        ctx = Mock()
        ctx.params = {"workflow_name": "wf", "namespace": "ma"}

        result = _get_cached_names(ctx, "all")
        assert result == ["cached.task"]
        mock_fetch.assert_not_called()

    @patch('console_link.workflow.commands.autocomplete_deployments._fetch_deployment_names')
    def test_refetches_when_cache_stale(self, mock_fetch, tmp_path, monkeypatch):
        cache_file = tmp_path / "all_wf.json"
        cache_file.write_text(json.dumps(["old.task"]))
        # Make cache appear old
        import os
        old_time = time.time() - 20
        os.utime(cache_file, (old_time, old_time))
        monkeypatch.setattr('console_link.workflow.commands.autocomplete_deployments._get_cache_file',
                            lambda wf, kind: cache_file)
        mock_fetch.return_value = ["new.task"]
        ctx = Mock()
        ctx.params = {"workflow_name": "wf", "namespace": "ma"}

        result = _get_cached_names(ctx, "all")
        assert result == ["new.task"]


class TestCompletionFunctions:
    @patch('console_link.workflow.commands.autocomplete_deployments._get_cached_names')
    def test_running_completions_filters_by_prefix(self, mock_cached):
        mock_cached.return_value = ["src.tgt.backfill", "src.tgt.replayer"]
        ctx = Mock()
        items = get_running_deployment_completions(ctx, None, "src.tgt.b")
        assert len(items) == 1
        assert items[0].value == "src.tgt.backfill"

    @patch('console_link.workflow.commands.autocomplete_deployments._get_cached_names')
    def test_paused_completions(self, mock_cached):
        mock_cached.return_value = ["src.tgt.replayer"]
        ctx = Mock()
        items = get_paused_deployment_completions(ctx, None, "")
        assert len(items) == 1

    @patch('console_link.workflow.commands.autocomplete_deployments._get_cached_names')
    def test_all_completions_empty_prefix(self, mock_cached):
        mock_cached.return_value = ["a.task", "b.task"]
        ctx = Mock()
        items = get_all_deployment_completions(ctx, None, "")
        assert len(items) == 2
