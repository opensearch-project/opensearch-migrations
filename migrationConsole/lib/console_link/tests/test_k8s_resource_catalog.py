import json

import pytest

from console_link.k8s_resource_catalog import (
    ConsoleConsumerGroupEntry,
    ConsoleResourceCatalog,
    ConsoleResourceEntry,
    ResourceRole,
    ResourceSelectionError,
)


def _cluster_config(endpoint):
    return {
        "endpoint": endpoint,
        "allow_insecure": True,
        "no_auth": None,
    }


class FakeCustomObjectsApi:
    def __init__(self, items_by_plural=None):
        self.items_by_plural = items_by_plural or {}

    def list_namespaced_custom_object(self, group, version, namespace, plural):
        return {"items": self.items_by_plural.get(plural, [])}


class FakeRunner:
    def __init__(self, outputs_by_arg, calls):
        self.outputs_by_arg = outputs_by_arg
        self.calls = calls

    def run_config_processor_node_script(self, processor_name, input_arg, temp_path):
        with open(temp_path) as f:
            self.calls.append((processor_name, input_arg, f.read()))
        return json.dumps(self.outputs_by_arg[input_arg])


class FakeWorkflowConfig:
    raw_yaml = "workflow: config\n"


def test_from_k8s_merges_config_and_latest_migration_run_with_run_values_preferred(monkeypatch):
    monkeypatch.setattr("console_link.k8s_resource_catalog.load_k8s_config", lambda: None)
    calls = []
    outputs = {
        "--user-config": {
            "sources": [{
                "refName": "source1",
                "aliases": ["source1"],
                "clientConfig": _cluster_config("https://config-source.example.com"),
            }],
            "targets": [{
                "refName": "target1",
                "aliases": ["target1"],
                "clientConfig": _cluster_config("https://config-target.example.com"),
            }],
            "kafkas": [],
            "consumerGroups": [],
        },
        "--resolved-config": {
            "sources": [{
                "refName": "source1",
                "aliases": ["source1"],
                "clientConfig": _cluster_config("https://run-source.example.com"),
            }],
            "targets": [{
                "refName": "target1",
                "aliases": ["target1"],
                "clientConfig": _cluster_config("https://run-target.example.com"),
            }],
            "kafkas": [],
            "consumerGroups": [{
                "name": "replayer-target1",
                "kafkaRef": "default",
                "targetRef": "target1",
                "replayRef": "replay1",
            }],
        },
    }
    custom = FakeCustomObjectsApi({
        "migrationruns": [
            {"metadata": {"name": "old-run"}, "spec": {"runNumber": 1, "resolvedConfig": {"marker": "old"}}},
            {"metadata": {"name": "new-run"}, "spec": {"runNumber": 2, "resolvedConfig": {"marker": "new"}}},
        ],
        "snapshotmigrations": [{
            "metadata": {
                "name": "source1-target1-snap-migration",
                "labels": {
                    "migrations.opensearch.org/source": "source1",
                    "migrations.opensearch.org/target": "target1",
                },
            },
            "status": {"phase": "Completed"},
        }],
    })

    catalog = ConsoleResourceCatalog.from_k8s(
        namespace="ma",
        config=FakeWorkflowConfig(),
        runner_factory=lambda: FakeRunner(outputs, calls),
        custom_api=custom,
    )

    assert catalog.resolve_cluster(ResourceRole.SOURCE).endpoint == "https://run-source.example.com"
    assert catalog.resolve_cluster(ResourceRole.TARGET).endpoint == "https://run-target.example.com"
    assert catalog.consumer_groups == [
        ConsoleConsumerGroupEntry(
            name="replayer-target1",
            kafka_ref="default",
            target_ref="target1",
            replay_ref="replay1",
        )
    ]
    assert catalog.consumer_group_names() == ["replayer-target1"]
    assert catalog.resolve(ResourceRole.SOURCE, "source1").origins == {"config", "migrationRun", "deployed"}
    assert any(call[1] == "--resolved-config" and '"new"' in call[2] for call in calls)


def test_resolve_requires_selector_when_multiple_resources_are_configured():
    catalog = ConsoleResourceCatalog([
        ConsoleResourceEntry(
            role=ResourceRole.SOURCE,
            ref_name="source1",
            aliases=["source1"],
            client_config=_cluster_config("https://source1.example.com"),
        ),
        ConsoleResourceEntry(
            role=ResourceRole.SOURCE,
            ref_name="source2",
            aliases=["source2"],
            client_config=_cluster_config("https://source2.example.com"),
        ),
    ])

    with pytest.raises(ResourceSelectionError, match="Multiple source resources"):
        catalog.resolve_cluster(ResourceRole.SOURCE)

    assert catalog.resolve_cluster(ResourceRole.SOURCE, "source2").endpoint == "https://source2.example.com"


def test_resolve_cluster_selector_accepts_unique_cluster_resource_name():
    catalog = ConsoleResourceCatalog([
        ConsoleResourceEntry(
            role=ResourceRole.SOURCE,
            ref_name="source1",
            aliases=["source1"],
            client_config=_cluster_config("https://source1.example.com"),
        ),
        ConsoleResourceEntry(
            role=ResourceRole.SOURCE,
            ref_name="source2",
            aliases=["source2"],
            client_config=_cluster_config("https://source2.example.com"),
        ),
        ConsoleResourceEntry(
            role=ResourceRole.TARGET,
            ref_name="target1",
            aliases=["target1"],
            client_config=_cluster_config("https://target1.example.com"),
        ),
    ])

    assert catalog.resolve_cluster_selector("source2").endpoint == "https://source2.example.com"
    assert catalog.resolve_cluster_selector("target1").endpoint == "https://target1.example.com"


def test_resolve_cluster_selector_role_shorthand_auto_selects_single_resource():
    catalog = ConsoleResourceCatalog([
        ConsoleResourceEntry(
            role=ResourceRole.SOURCE,
            ref_name="source-a",
            aliases=["source-a"],
            client_config=_cluster_config("https://source-a.example.com"),
        ),
        ConsoleResourceEntry(
            role=ResourceRole.TARGET,
            ref_name="target-a",
            aliases=["target-a"],
            client_config=_cluster_config("https://target-a.example.com"),
        ),
    ])

    assert catalog.resolve_cluster_selector("source").endpoint == "https://source-a.example.com"
    assert catalog.resolve_cluster_selector("target").endpoint == "https://target-a.example.com"


def test_resolve_cluster_selector_reports_cross_role_ambiguity():
    catalog = ConsoleResourceCatalog([
        ConsoleResourceEntry(
            role=ResourceRole.SOURCE,
            ref_name="shared",
            aliases=["shared"],
            client_config=_cluster_config("https://source.example.com"),
        ),
        ConsoleResourceEntry(
            role=ResourceRole.TARGET,
            ref_name="shared",
            aliases=["shared"],
            client_config=_cluster_config("https://target.example.com"),
        ),
    ])

    with pytest.raises(ResourceSelectionError, match="matches multiple resources"):
        catalog.resolve_cluster_selector("shared")


def test_cluster_selector_values_only_advertise_role_shorthand_for_singletons():
    catalog = ConsoleResourceCatalog([
        ConsoleResourceEntry(
            role=ResourceRole.SOURCE,
            ref_name="source-a",
            aliases=["source-a"],
            client_config=_cluster_config("https://source-a.example.com"),
        ),
        ConsoleResourceEntry(
            role=ResourceRole.SOURCE,
            ref_name="source-b",
            aliases=["source-b"],
            client_config=_cluster_config("https://source-b.example.com"),
        ),
        ConsoleResourceEntry(
            role=ResourceRole.TARGET,
            ref_name="targeta",
            aliases=["targeta"],
            client_config=_cluster_config("https://targeta.example.com"),
        ),
        ConsoleResourceEntry(
            role=ResourceRole.TARGET,
            ref_name="targetb",
            aliases=["targetb"],
            client_config=_cluster_config("https://targetb.example.com"),
        ),
        ConsoleResourceEntry(
            role=ResourceRole.PROXY,
            ref_name="proxy-a",
            aliases=["proxy-a", "captureproxy.proxy-a"],
            k8s_name="proxy-a",
            client_config=_cluster_config("https://proxy-a.example.com"),
        ),
        ConsoleResourceEntry(
            role=ResourceRole.PROXY,
            ref_name="proxy-b",
            aliases=["proxy-b", "captureproxy.proxy-b"],
            k8s_name="proxy-b",
            client_config=_cluster_config("https://proxy-b.example.com"),
        ),
    ])

    assert catalog.cluster_selector_values() == [
        "proxy-a",
        "proxy-b",
        "source-a",
        "source-b",
        "targeta",
        "targetb",
    ]
    assert catalog.selector_values(ResourceRole.PROXY) == [
        "proxy-a",
        "proxy-b",
    ]


def test_deployed_source_and_target_labels_are_tracked_without_client_config():
    catalog = ConsoleResourceCatalog()
    catalog.overlay_live_resources(
        FakeCustomObjectsApi({
            "trafficreplays": [{
                "metadata": {
                    "name": "replay1",
                    "labels": {
                        "migrations.opensearch.org/source": "source1",
                        "migrations.opensearch.org/target": "target1",
                    },
                },
                "status": {"phase": "Running"},
            }],
        }),
        namespace="ma",
    )

    assert catalog.resolve(ResourceRole.SOURCE, "source1").origins == {"deployed"}
    assert catalog.resolve(ResourceRole.TARGET, "target1").origins == {"deployed"}
    with pytest.raises(ResourceSelectionError, match="has no client configuration"):
        catalog.resolve_cluster(ResourceRole.SOURCE, "source1")


def test_source_proxy_config_is_sanitized_for_source_cluster_schema():
    catalog = ConsoleResourceCatalog._from_resolver_output({
        "sources": [{
            "refName": "source",
            "aliases": ["source"],
            "clientConfig": {
                "endpoint": "https://source.example.com",
                "allow_insecure": True,
                "version": "ES 7.10.2",
                "basic_auth": {"k8s_secret_name": "source-basic"},
            },
            "proxy": {
                "refName": "source-proxy",
                "k8sName": "source-proxy",
                "aliases": ["source-proxy"],
                "clientConfig": {
                    "endpoint": "https://source-proxy:9201",
                    "allow_insecure": True,
                    "version": "ES 7.10.2",
                    "basic_auth": {"k8s_secret_name": "source-basic"},
                    "client_cert": {"k8s_secret_name": "proxy-client-cert"},
                },
            },
        }],
        "targets": [],
        "kafkas": [],
        "consumerGroups": [],
    })

    source_entry = catalog.resolve(ResourceRole.SOURCE, "source")
    assert source_entry.proxy_config == {
        "endpoint": "https://source-proxy:9201",
        "allow_insecure": True,
        "client_cert": {"k8s_secret_name": "proxy-client-cert"},
    }
    proxy_entry = catalog.resolve(ResourceRole.PROXY, "source-proxy")
    assert proxy_entry.client_config["basic_auth"] == {"k8s_secret_name": "source-basic"}

    source_cluster = catalog.resolve_cluster(ResourceRole.SOURCE, "source")
    assert source_cluster.proxy.endpoint == "https://source-proxy:9201"
    assert source_cluster.proxy.auth_details == {"k8s_secret_name": "source-basic"}
    assert source_cluster.proxy.client_cert_details == {"k8s_secret_name": "proxy-client-cert"}


def test_k8s_selector_values_hide_k8s_aliases_but_still_accept_legacy_names():
    catalog = ConsoleResourceCatalog([
        ConsoleResourceEntry(
            role=ResourceRole.PROXY,
            ref_name="source-proxy",
            aliases=[
                "source-proxy",
                "captureproxy.source-proxy",
                "captureproxies.migrations.opensearch.org/source-proxy",
            ],
            k8s_name="source-proxy",
            client_config=_cluster_config("https://source-proxy:9201"),
        ),
        ConsoleResourceEntry(
            role=ResourceRole.KAFKA,
            ref_name="default",
            aliases=[
                "default",
                "kafkacluster.default",
                "kafkaclusters.migrations.opensearch.org/default",
            ],
            k8s_name="default",
            kafka_runtime={"type": "strimzi", "clusterName": "default"},
        ),
    ])

    proxy_selectors = catalog.selector_values(ResourceRole.PROXY)
    kafka_selectors = catalog.selector_values(ResourceRole.KAFKA)

    assert "captureproxies.migrations.opensearch.org/source-proxy" not in proxy_selectors
    assert "captureproxy.source-proxy" not in proxy_selectors
    assert "kafkaclusters.migrations.opensearch.org/default" not in kafka_selectors
    assert "kafkacluster.default" not in kafka_selectors
    assert "source-proxy" in proxy_selectors
    assert "default" in kafka_selectors

    assert catalog.resolve(ResourceRole.PROXY, "captureproxy.source-proxy").ref_name == "source-proxy"
    assert catalog.resolve(
        ResourceRole.PROXY,
        "captureproxies.migrations.opensearch.org/source-proxy",
    ).ref_name == "source-proxy"
    assert catalog.resolve(ResourceRole.KAFKA, "kafkacluster.default").ref_name == "default"
    assert catalog.resolve(ResourceRole.PROXY, "captureproxies/source-proxy").ref_name == "source-proxy"
    assert catalog.resolve(ResourceRole.PROXY, "captureproxy/source-proxy").ref_name == "source-proxy"
    assert catalog.resolve(
        ResourceRole.KAFKA,
        "kafkaclusters.migrations.opensearch.org/default",
    ).ref_name == "default"
    assert catalog.resolve(ResourceRole.KAFKA, "kafkaclusters/default").ref_name == "default"


def test_direct_scram_kafka_uses_secret_metadata(monkeypatch):
    calls = []

    def fake_resolve_credentials(username_secret, ca_secret, namespace=None):
        calls.append((username_secret, ca_secret, namespace))
        return "secret-password", "/tmp/kafka-ca.crt"

    monkeypatch.setattr(
        "console_link.k8s_resource_catalog._resolve_strimzi_scram_credentials",
        fake_resolve_credentials,
    )
    catalog = ConsoleResourceCatalog([
        ConsoleResourceEntry(
            role=ResourceRole.KAFKA,
            ref_name="external",
            aliases=["external"],
            kafka_runtime={
                "type": "direct",
                "clientConfig": {
                    "broker_endpoints": "broker.example.com:9093",
                    "scram": {"username": "migration-app"},
                },
                "secretName": "external-kafka-user",
                "caSecretName": "external-kafka-ca",
            },
        ),
    ], namespace="ma")

    kafka = catalog.resolve_kafka("external")

    assert kafka.username == "migration-app"
    assert kafka.password == "secret-password"
    assert kafka.ca_cert_path == "/tmp/kafka-ca.crt"
    assert calls == [("external-kafka-user", "external-kafka-ca", "ma")]
