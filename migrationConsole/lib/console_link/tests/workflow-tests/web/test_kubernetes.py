import os
from pathlib import Path
from types import SimpleNamespace

from kubernetes import client, config

from console_link.workflow.web.kubernetes import pin_kubernetes_runtime


def test_local_runtime_pins_api_and_subprocesses_to_kubeconfig_snapshot(
    monkeypatch,
):
    snapshot = """
apiVersion: v1
kind: Config
current-context: kind-config-ui
clusters: []
contexts: []
users: []
"""
    monkeypatch.setenv("KUBECONFIG", "/original/config")
    monkeypatch.setattr(
        client.Configuration,
        "_default",
        client.Configuration._default,
    )
    monkeypatch.setattr(
        config,
        "load_incluster_config",
        lambda: (_ for _ in ()).throw(config.ConfigException()),
    )
    monkeypatch.setattr(
        "console_link.workflow.web.kubernetes.subprocess.run",
        lambda *_args, **_kwargs: SimpleNamespace(stdout=snapshot),
    )

    def load_snapshot(*, config_file):
        assert config_file != "/original/config"
        assert Path(config_file).read_text() == snapshot
        configuration = client.Configuration()
        configuration.host = "https://kind-control-plane"
        client.Configuration.set_default(configuration)

    monkeypatch.setattr(config, "load_kube_config", load_snapshot)

    with pin_kubernetes_runtime() as runtime:
        pinned_path = runtime.kubeconfig_path
        assert runtime.context_name == "kind-config-ui"
        assert runtime.api_client.configuration.host == (
            "https://kind-control-plane"
        )
        assert os.environ["KUBECONFIG"] == str(pinned_path)
        assert runtime.subprocess_env["KUBECONFIG"] == str(pinned_path)

        replacement = client.Configuration()
        replacement.host = "https://different-cluster"
        client.Configuration.set_default(replacement)
        monkeypatch.setenv("KUBECONFIG", "/changed/after-startup")

        assert runtime.api_client.configuration.host == (
            "https://kind-control-plane"
        )
        assert runtime.subprocess_env["KUBECONFIG"] == str(pinned_path)

    assert os.environ["KUBECONFIG"] == "/original/config"
    assert not pinned_path.exists()
