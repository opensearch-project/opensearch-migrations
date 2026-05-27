import integ_test.test_cases.cdc_base as cdc_base
from console_link.models.cluster import AuthMethod, Cluster
from integ_test.test_cases.cdc_base import PROXY_ENDPOINT, make_proxy_cluster


def test_make_proxy_cluster_signs_sigv4_requests_with_source_endpoint():
    source = Cluster({
        "endpoint": "https://search-source.us-east-1.es.amazonaws.com",
        "allow_insecure": True,
        "version": "ES_7.10",
        "sigv4": {
            "region": "us-east-1",
            "service": "es",
        },
    })

    proxy = make_proxy_cluster(source)

    assert proxy.endpoint == PROXY_ENDPOINT
    assert proxy.allow_insecure
    assert proxy.auth_type == AuthMethod.SIGV4
    assert proxy.config["sigv4_signing_endpoint"] == source.endpoint


def test_wait_for_replayer_consuming_waits_for_pod_ready_first(monkeypatch):
    ready_calls = []

    def fake_wait_for_pod_ready(namespace, label_selector, timeout_seconds):
        ready_calls.append((namespace, label_selector, timeout_seconds))

    class FakeCompletedProcess:
        stdout = "KafkaHeartbeat partitions=[0]\n"

    run_calls = []

    def fake_run(args, **kwargs):
        run_calls.append((args, kwargs))
        return FakeCompletedProcess()

    monkeypatch.setattr(cdc_base, "wait_for_pod_ready", fake_wait_for_pod_ready)
    monkeypatch.setattr(cdc_base.subprocess, "run", fake_run)

    cdc_base.wait_for_replayer_consuming(
        namespace="ma",
        timeout_seconds=1,
        pod_ready_timeout_seconds=42,
    )

    assert ready_calls == [("ma", cdc_base.REPLAYER_LABEL_SELECTOR, 42)]
    assert run_calls
