"""Covers workflowConfigToServicesConfig.jq, the translator the workflow's console containers run
over the Argo-emitted config before console_link reads it. Exercised here rather than in
orchestrationSpecs because the assertion that matters is that console_link can consume the output.
"""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

from console_link.models.cluster import Cluster

JQ_SCRIPT = Path(__file__).resolve().parents[3] / "workflowConfigToServicesConfig.jq"

pytestmark = pytest.mark.skipif(shutil.which("jq") is None, reason="jq is not installed")


def translate(workflow_config: dict) -> dict:
    result = subprocess.run(
        ["jq", "-f", str(JQ_SCRIPT)],
        input=json.dumps(workflow_config),
        capture_output=True,
        text=True,
        check=True,
    )
    return json.loads(result.stdout)


def solr_workflow_config(**source_overrides) -> dict:
    source = {
        "label": "solr-source",
        "version": "SOLR 9.4",
        "endpoint": "https://solr:8983",
        "allowInsecure": True,
        "authConfig": {"basic": {"secretName": "solr-creds"}},
    }
    source.update(source_overrides)
    return {
        "source_cluster": source,
        "snapshot": {
            "snapshotName": "solr-backup-1",
            "label": "backup",
            "repoConfig": {
                "repoName": "solr-repo",
                "repoPathUri": "s3://my-bucket/solr",
                "awsRegion": "us-east-1",
            },
        },
    }


def test_jq_script_exists():
    assert JQ_SCRIPT.is_file(), f"expected the translator at {JQ_SCRIPT}"


def test_solr_context_path_reaches_source_cluster():
    services = translate(solr_workflow_config(solrContextPath="/tenant-a/solr"))

    assert services["source_cluster"]["solr_context_path"] == "/tenant-a/solr"
    assert "solrContextPath" not in services["source_cluster"]


def test_translated_source_cluster_initializes_a_cluster_with_the_context_path():
    """The regression that motivated this file: the monitor cronjob builds a Cluster from this
    output, so a key the translator drops silently becomes a default-/solr poll."""
    services = translate(solr_workflow_config(solrContextPath="/tenant-a/solr"))

    cluster = Cluster(config=services["source_cluster"])

    assert cluster.solr_context_path == "/tenant-a/solr"
    assert cluster.version == "SOLR 9.4"


def test_empty_context_path_survives_translation():
    services = translate(solr_workflow_config(solrContextPath=""))

    assert services["source_cluster"]["solr_context_path"] == ""
    assert Cluster(config=services["source_cluster"]).solr_context_path == ""


def test_unset_context_path_defaults_to_solr():
    services = translate(solr_workflow_config())

    assert "solr_context_path" not in services["source_cluster"]
    assert Cluster(config=services["source_cluster"]).solr_context_path == "/solr"


def test_context_path_is_normalized_by_the_cluster():
    services = translate(solr_workflow_config(solrContextPath="tenant-a/solr/"))

    assert Cluster(config=services["source_cluster"]).solr_context_path == "/tenant-a/solr"
