import base64
from pathlib import Path

import pytest
import yaml

from integ_test.test_cases.byoc_captured_traffic_tests import (
    KAFKA_TOPIC_NAME,
    _make_migration_config_base64,
)


INTEG_TEST_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = Path(__file__).resolve().parents[4]


def _workflow_template_script(filename: str, template_name: str) -> str:
    workflow = yaml.safe_load(
        (INTEG_TEST_ROOT / "testWorkflows" / filename).read_text(encoding="utf-8")
    )
    template = next(
        item for item in workflow["spec"]["templates"] if item["name"] == template_name
    )
    return template["container"]["args"][0]


@pytest.mark.parametrize(
    ("filename", "template_name"),
    [
        ("cdcOverlay.yaml", "add-traffic-config"),
        ("cdcOverlay.yaml", "add-proxy-only-traffic-config"),
        ("cdcOnlyImportedClusters.yaml", "build-cdc-only-config"),
    ],
)
def test_cdc_workflow_configs_bind_capture_proxy_to_explicit_kafka_topic(
    filename: str,
    template_name: str,
):
    script = _workflow_template_script(filename, template_name)

    assert '"topics": {' in script
    assert '"capture-proxy": {}' in script
    assert '"kafka": "default"' in script
    assert '"kafkaTopic": "capture-proxy"' in script


def test_local_cdc_load_config_uses_explicit_kafka_topic_reference():
    config = yaml.safe_load(
        (
            REPOSITORY_ROOT / "deployment" / "k8s" / "configs" / "cdcLoadTest.yaml"
        ).read_text(encoding="utf-8")
    )

    assert "kafkaClusterConfiguration" not in config
    cluster = config["traffic"]["kafkaClusters"]["default"]
    assert cluster["topics"]["capture-proxy"] == {}
    proxy = config["traffic"]["proxies"]["capture-proxy"]
    assert proxy["kafka"] == "default"
    assert proxy["kafkaTopic"] == "capture-proxy"


def test_byoc_s3_config_binds_source_to_explicit_kafka_topic():
    config = yaml.safe_load(base64.b64decode(_make_migration_config_base64()))

    cluster = config["traffic"]["kafkaClusters"]["default"]
    assert cluster["topics"][KAFKA_TOPIC_NAME] == {}
    source = config["traffic"]["s3Sources"]["byoc-put"]
    assert source["kafka"] == "default"
    assert source["kafkaTopic"] == KAFKA_TOPIC_NAME
