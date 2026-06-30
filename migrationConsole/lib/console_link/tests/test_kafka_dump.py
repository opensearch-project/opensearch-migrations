"""Unit tests for the one-shot Kafka dump-pod launcher (kafka_dump.py).

These exercise pure manifest rendering plus the kubectl apply/logs/delete
sequencing, with subprocess and the k8s client mocked. No cluster required.
"""
import subprocess
import types

import pytest

from console_link.models import kafka_dump
from console_link.models.kafka import ScramKafka, StandardKafka, MSK
from console_link.models.kafka_dump import (
    build_dump_pod_manifest,
    launch_dump_pod,
    _derive_cluster_name,
    KafkaDumpError,
    SCRAM_PASSWORD_ENV_VAR,
    KAFKA_AUTH_CONFIG_FILE_PATH,
)


def _scram(monkeypatch, username="default-migration-app",
           brokers="default-kafka-bootstrap.ma.svc:9093", ca="/tmp/ca.crt"):
    monkeypatch.setenv("KAFKA_SCRAM_PASSWORD", "secret-pw")
    cfg = {"broker_endpoints": brokers, "scram": {"username": username, "ca_cert_path": ca}}
    return ScramKafka(cfg, password="secret-pw")


def _standard(brokers="default-kafka-bootstrap.ma.svc:9092"):
    return StandardKafka({"broker_endpoints": brokers, "standard": None})


def _msk(brokers="b-1.msk.amazonaws.com:9098"):
    return MSK({"broker_endpoints": brokers, "msk": None})


# --------------------------- cluster-name derivation --------------------------

def test_derive_cluster_name_from_scram_username(monkeypatch):
    assert _derive_cluster_name(_scram(monkeypatch)) == "default"


def test_derive_cluster_name_from_bootstrap_host():
    assert _derive_cluster_name(_standard("mycluster-kafka-bootstrap.ma.svc:9092")) == "mycluster"


def test_derive_cluster_name_unknown():
    assert _derive_cluster_name(_standard("some-random-host:9092")) is None


# ------------------------------ manifest: SCRAM -------------------------------

def test_scram_manifest_password_via_env_not_argv(monkeypatch):
    k = _scram(monkeypatch)
    m = build_dump_pod_manifest(
        k, pod_name="kafka-dump-capture-proxy-1", namespace="ma",
        image="ecr/traffic_replayer:tag", pull_policy="IfNotPresent",
        cluster_name="default", topic="capture-proxy", mode="dump-both",
        start_offset=7, end_offset=640, kafka_auth_configmap="capture-proxy-kafka-auth",
    )
    container = m["spec"]["containers"][0]
    # password never appears in argv
    assert all("secret-pw" not in a for a in container["args"])
    # password injected via the same env var the replayer uses, from the secret
    env = {e["name"]: e for e in container["env"]}
    assert SCRAM_PASSWORD_ENV_VAR in env
    ref = env[SCRAM_PASSWORD_ENV_VAR]["valueFrom"]["secretKeyRef"]
    assert ref == {"name": "default-migration-app", "key": "password"}


def test_scram_manifest_args_and_mounts(monkeypatch):
    k = _scram(monkeypatch)
    m = build_dump_pod_manifest(
        k, pod_name="p", namespace="ma", image="img", pull_policy="Always",
        cluster_name="default", topic="capture-proxy", mode="dump-both",
        start_offset=7, end_offset=640, start_time=None, end_time=None,
        kafka_auth_configmap="capture-proxy-kafka-auth",
    )
    args = m["spec"]["containers"][0]["args"]
    assert args[:6] == ["--mode", "dump-both",
                        "--kafka-traffic-brokers", "default-kafka-bootstrap.ma.svc:9093",
                        "--kafka-traffic-topic", "capture-proxy"]
    assert "--kafkaAuthType" in args and "scram-sha-512" in args
    assert "--kafkaUserName" in args and "default-migration-app" in args
    assert "--kafka-traffic-property-file" in args
    assert args[args.index("--kafka-traffic-property-file") + 1] == KAFKA_AUTH_CONFIG_FILE_PATH
    assert args[args.index("--start-offset") + 1] == "7"
    assert args[args.index("--end-offset") + 1] == "640"
    vols = {v["name"]: v for v in m["spec"]["volumes"]}
    assert vols["kafka-auth-config"]["configMap"]["name"] == "capture-proxy-kafka-auth"
    assert vols["kafka-ca"]["secret"]["secretName"] == "default-cluster-ca-cert"


def test_scram_manifest_time_bounds(monkeypatch):
    k = _scram(monkeypatch)
    m = build_dump_pod_manifest(
        k, pod_name="p", namespace="ma", image="img", pull_policy="IfNotPresent",
        cluster_name="default", topic="capture-proxy", mode="dump-raw",
        start_time=1000, end_time=2000, kafka_auth_configmap="capture-proxy-kafka-auth",
    )
    args = m["spec"]["containers"][0]["args"]
    assert args[args.index("--start-time") + 1] == "1000"
    assert args[args.index("--end-time") + 1] == "2000"
    assert "--start-offset" not in args and "--end-offset" not in args


def test_scram_without_configmap_omits_property_file(monkeypatch):
    """If no kafka-auth ConfigMap is available, we don't pass the property file
    flag (and don't mount the volumes) — auth would then fail loudly rather
    than silently pointing at a missing file."""
    k = _scram(monkeypatch)
    m = build_dump_pod_manifest(
        k, pod_name="p", namespace="ma", image="img", pull_policy="IfNotPresent",
        cluster_name="default", topic="capture-proxy", mode="dump-both",
        kafka_auth_configmap=None,
    )
    args = m["spec"]["containers"][0]["args"]
    assert "--kafka-traffic-property-file" not in args
    assert "volumes" not in m["spec"]


# --------------------------- manifest: standard/MSK ---------------------------

def test_standard_manifest_no_auth_no_volumes():
    k = _standard()
    m = build_dump_pod_manifest(
        k, pod_name="p", namespace="ma", image="img", pull_policy="IfNotPresent",
        cluster_name="default", topic="capture-proxy", mode="dump-both",
    )
    container = m["spec"]["containers"][0]
    assert "--kafkaAuthType" not in container["args"]
    assert "env" not in container
    assert "volumes" not in m["spec"]


def test_msk_manifest_iam_auth():
    k = _msk()
    m = build_dump_pod_manifest(
        k, pod_name="p", namespace="ma", image="img", pull_policy="IfNotPresent",
        cluster_name=None, topic="capture-proxy", mode="dump-both",
    )
    args = m["spec"]["containers"][0]["args"]
    assert args[args.index("--kafkaAuthType") + 1] == "msk-iam"
    assert "volumes" not in m["spec"]


# ------------------------------ common manifest -------------------------------

@pytest.mark.parametrize("kafka_factory", ["scram", "standard", "msk"])
def test_manifest_common_shape(monkeypatch, kafka_factory):
    k = {"scram": lambda: _scram(monkeypatch), "standard": _standard, "msk": _msk}[kafka_factory]()
    m = build_dump_pod_manifest(
        k, pod_name="kafka-dump-x", namespace="ns", image="img", pull_policy="IfNotPresent",
        cluster_name="default", topic="t", mode="dump-both",
        kafka_auth_configmap="capture-proxy-kafka-auth",
    )
    assert m["apiVersion"] == "v1" and m["kind"] == "Pod"
    assert m["spec"]["restartPolicy"] == "Never"
    assert m["metadata"]["annotations"]["karpenter.sh/do-not-disrupt"] == "true"
    assert m["metadata"]["namespace"] == "ns"


# ------------------------------ launch sequencing -----------------------------

class _FakeCore:
    def __init__(self, image="ecr/traffic_replayer:tag", cm_names=("capture-proxy-kafka-auth",)):
        self._image = image
        self._cm_names = cm_names

    def read_namespaced_config_map(self, name, namespace):
        assert name == "migration-image-config"
        return types.SimpleNamespace(data={
            "trafficReplayerImage": self._image,
            "trafficReplayerPullPolicy": "IfNotPresent",
        })

    def list_namespaced_config_map(self, namespace):
        items = [types.SimpleNamespace(metadata=types.SimpleNamespace(name=n)) for n in self._cm_names]
        return types.SimpleNamespace(items=items)


def _patch_k8s(mocker, core=None):
    core = core or _FakeCore()
    fake_client = types.SimpleNamespace(CoreV1Api=lambda: core)
    mocker.patch.object(kafka_dump, "_load_k8s", return_value=fake_client)
    return core


def _completed(returncode=0, stdout="", stderr=""):
    return subprocess.CompletedProcess(args=[], returncode=returncode, stdout=stdout, stderr=stderr)


def test_launch_applies_streams_and_deletes(monkeypatch, mocker):
    _patch_k8s(mocker)
    k = _scram(monkeypatch)
    calls = []

    def fake_run(cmd, **kwargs):
        calls.append(cmd)
        if "apply" in cmd:
            return _completed(0, stdout="pod/kafka-dump created")
        if "wait" in cmd:
            return _completed(0)
        if "logs" in cmd:
            return _completed(0, stdout="msg 0: GET /_cat/indices\nmsg 1: ...")
        if "delete" in cmd:
            return _completed(0)
        return _completed(0)

    mocker.patch("subprocess.run", side_effect=fake_run)
    out = []
    res = launch_dump_pod(k, namespace="ma", topic="capture-proxy", mode="dump-both",
                          start_offset=7, end_offset=640, echo=out.append)
    assert res.success
    verbs = [c[c.index("-n") + 2] if "-n" in c else c for c in calls]  # noqa
    joined = [" ".join(c) for c in calls]
    assert any("apply -f -" in j for j in joined)
    assert any("logs -f" in j for j in joined)
    assert any("delete pod" in j for j in joined)
    # log content surfaced to the console
    assert any("GET /_cat/indices" in line for line in out)


def test_launch_deletes_pod_even_when_logs_fail(monkeypatch, mocker):
    _patch_k8s(mocker)
    k = _scram(monkeypatch)
    seen = {"deleted": False}

    def fake_run(cmd, **kwargs):
        if "apply" in cmd:
            return _completed(0)
        if "wait" in cmd:
            return _completed(0)
        if "logs" in cmd:
            raise subprocess.TimeoutExpired(cmd=cmd, timeout=1)
        if "delete" in cmd:
            seen["deleted"] = True
            return _completed(0)
        return _completed(0)

    mocker.patch("subprocess.run", side_effect=fake_run)
    res = launch_dump_pod(k, namespace="ma", topic="capture-proxy", echo=lambda *_: None)
    assert not res.success
    assert seen["deleted"], "dump pod must be deleted even when the log stream times out"


def test_launch_apply_failure_returns_error(monkeypatch, mocker):
    _patch_k8s(mocker)
    k = _scram(monkeypatch)

    def fake_run(cmd, **kwargs):
        if "apply" in cmd:
            return _completed(1, stderr="admission webhook denied the request")
        return _completed(0)

    mocker.patch("subprocess.run", side_effect=fake_run)
    res = launch_dump_pod(k, namespace="ma", topic="capture-proxy", echo=lambda *_: None)
    assert not res.success
    assert "admission webhook" in res.value


def test_launch_invalid_mode_raises(monkeypatch, mocker):
    _patch_k8s(mocker)
    k = _scram(monkeypatch)
    with pytest.raises(KafkaDumpError):
        launch_dump_pod(k, namespace="ma", topic="capture-proxy", mode="not-a-mode")


def test_launch_scram_no_configmap_raises(monkeypatch, mocker):
    _patch_k8s(mocker, core=_FakeCore(cm_names=()))  # no *-kafka-auth present
    k = _scram(monkeypatch)
    mocker.patch("subprocess.run", side_effect=AssertionError("should not apply"))
    with pytest.raises(KafkaDumpError, match="kafka-auth"):
        launch_dump_pod(k, namespace="ma", topic="capture-proxy")
