import json
import logging
import socket
import subprocess
import time
import uuid

import yaml
from kubernetes import client

from console_link.models.cluster import Cluster
from console_link.workflow.commands.crd_utils import CRD_GROUP, CRD_VERSION
from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS, PROXY_PORT, PROXY_RESOURCE_NAME, PROXY_SERVICE_NAME,
    assert_replay_drained, load_k8s_config, make_proxy_cluster, wait_for_proxy_ready,
    wait_for_replayer_consuming,
)

logger = logging.getLogger(__name__)

NUM_DOCS = 5
NEW_PROXY_PORT = 9202
OVERRIDE_FLAG = "--override-change-restrictions"
LISTEN_PORT_OVERRIDE_ANNOTATION = "change-restriction-override.migrations.opensearch.org/listenPort"
RUN_NUMBER_LABEL = "migrations.opensearch.org/run-number"
PROXY_GATE_NAME = f"captureproxy.{PROXY_RESOURCE_NAME}.vapretry"
PROXY_REF = f"captureproxy.{PROXY_RESOURCE_NAME}"
GATE_TIMEOUT_SECONDS = 15 * 60
APPLY_TIMEOUT_SECONDS = 15 * 60


def run_workflow_cli(args: list, stdin: str = None, timeout: int = 180) -> subprocess.CompletedProcess:
    """Run the `workflow` CLI. The caller decides whether a non-zero exit is a failure."""
    return subprocess.run(["workflow"] + args, input=stdin, capture_output=True, text=True, timeout=timeout)


def run_workflow_cli_ok(args: list, stdin: str = None) -> str:
    result = run_workflow_cli(args, stdin=stdin)
    if result.returncode != 0:
        raise AssertionError(
            f"workflow {' '.join(args)} failed ({result.returncode}): {result.stderr or result.stdout}")
    return result.stdout


class Test0036CdcChangeRestrictionOverride(MATestBase):
    """Changes an Impossible field on a running CaptureProxy with the hidden change-restriction override.

    A CDC migration runs and replays traffic. The test then resubmits it with a different proxy listenPort,
    which the admission policies refuse on a running proxy, so the workflow parks on the proxy's retry gate. A plain
    `workflow approve retry` still demands a reset; `workflow approve retry --override-change-restrictions` annotates
    the CaptureProxy for this run and approves the gate, and the retried apply goes through. Traffic sent to the new
    port is then captured and replayed, and the old port no longer answers.
    """
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Override an Impossible CaptureProxy change on a running migration with the real command.",
            migrations_required=[MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )
        suffix = f"{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.index_before = f"cdc0036-before-{suffix}"
        self.index_after = f"cdc0036-after-{suffix}"
        # The test parks the workflow on this gate on purpose; any other parked gate still fails it.
        self.argo_service.expected_parked_gate_names = [PROXY_GATE_NAME]

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = (
            "cdc-full-e2e-imported-clusters" if self.imported_clusters
            else "cdc-e2e-migration-with-clusters"
        )
        self.parameters["pre-snapshot-proxy-submit"] = "true"
        self.parameters["capture-proxy-service-type"] = self.capture_proxy_service_type

    def prepare_clusters(self):
        pass  # All documents go through the proxy.

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        """Bring up the proxy and replayer the way Test0035 does, then leave the outer workflow suspended."""
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        namespace = self.argo_service.namespace
        if not self.imported_clusters:
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)  # past pause-for-test-data
        wait_for_proxy_ready(namespace, timeout_seconds, workflow_name=self.workflow_name)
        if not self.imported_clusters:
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=600)
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)  # submit the full migration
        wait_for_replayer_consuming(namespace=namespace, workflow_name=self.workflow_name)
        if not self.imported_clusters:
            # pause-for-migration-verification
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=600)

    def post_migration_actions(self):
        namespace = self.argo_service.namespace
        self._send_docs(make_proxy_cluster(self.source_cluster), self.index_before)
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster, expected_index_details={self.index_before: {"count": NUM_DOCS}},
            max_attempts=60, delay=10.0)

        self._resubmit_with_listen_port(NEW_PROXY_PORT)
        gate = self._wait_for_retry_gate()
        assert "listenPort" in (gate.get("reason") or ""), f"unexpected denial: {gate}"

        # Without the override, a retry still demands a reset and leaves the proxy alone.
        plain = run_workflow_cli(["approve", "retry", PROXY_REF])
        assert plain.returncode != 0, f"plain retry should refuse without a reset: {plain.stdout}"
        assert "Cannot approve retry gates" in plain.stderr, plain.stderr
        assert self._proxy()["spec"]["listenPort"] == PROXY_PORT

        run_workflow_cli_ok(["approve", "retry", PROXY_REF, OVERRIDE_FLAG, "--yes"])
        gate_message = self._gate_status_message()
        assert gate_message.startswith("change-restriction override: listenPort by "), gate_message

        self._wait_for_proxy_on_port(NEW_PROXY_PORT)
        # The policy admits the change only when the override names the run applying it, whose label the proxy
        # carries once the retried apply lands.
        proxy = self._proxy()
        run_number = proxy["metadata"]["labels"][RUN_NUMBER_LABEL]
        assert proxy["metadata"]["annotations"].get(LISTEN_PORT_OVERRIDE_ANNOTATION) == run_number, \
            f"override annotation missing or not for run {run_number}: {proxy['metadata']['annotations']}"
        wait_for_replayer_consuming(namespace=namespace, workflow_name=self.workflow_name)
        self._send_docs(self._proxy_cluster(NEW_PROXY_PORT), self.index_after)
        assert not self._port_answers(PROXY_SERVICE_NAME, PROXY_PORT), \
            f"{PROXY_SERVICE_NAME}:{PROXY_PORT} still answers after the port change"

    def verify_clusters(self):
        try:
            self.target_operations.check_doc_counts_match(
                cluster=self.target_cluster,
                expected_index_details={self.index_before: {"count": NUM_DOCS},
                                        self.index_after: {"count": NUM_DOCS}},
                max_attempts=120, delay=10.0)
        finally:
            assert_replay_drained(label="replay-end")

    def _send_docs(self, proxy_cluster, index_name):
        for i in range(NUM_DOCS):
            self.source_operations.create_document(cluster=proxy_cluster, index_name=index_name, doc_id=f"doc_{i}")
        self.source_operations.check_doc_counts_match(
            cluster=self.source_cluster, expected_index_details={index_name: {"count": NUM_DOCS}},
            max_attempts=5, delay=2.0)

    def _proxy_cluster(self, port):
        """make_proxy_cluster, pointed at another port."""
        config = {**make_proxy_cluster(self.source_cluster).config, "endpoint": f"https://{PROXY_SERVICE_NAME}:{port}"}
        return Cluster(config=config)

    def _resubmit_with_listen_port(self, port):
        """Change the proxy's listenPort in the session's config and resubmit, as a user reconfiguring would."""
        config = yaml.safe_load(run_workflow_cli_ok(["configure", "view"]))
        config["traffic"]["proxies"][PROXY_RESOURCE_NAME]["proxyConfig"]["listenPort"] = port
        run_workflow_cli_ok(["configure", "edit", "--stdin"], stdin=yaml.safe_dump(config))
        run_workflow_cli_ok(["submit"])

    def _wait_for_retry_gate(self) -> dict:
        deadline = time.monotonic() + GATE_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            result = run_workflow_cli(["approve", "retry", "--list", "--output", "json"])
            if result.returncode == 0 and result.stdout.strip():
                for gate in json.loads(result.stdout):
                    if gate.get("name") == PROXY_GATE_NAME:
                        logger.info("Proxy retry gate parked: %s", gate.get("reason"))
                        return gate
            time.sleep(10)
        raise AssertionError(f"{PROXY_GATE_NAME} didn't park within {GATE_TIMEOUT_SECONDS}s")

    def _custom_objects(self):
        load_k8s_config()
        return client.CustomObjectsApi()

    def _proxy(self) -> dict:
        return self._custom_objects().get_namespaced_custom_object(
            CRD_GROUP, CRD_VERSION, self.argo_service.namespace, "captureproxies", PROXY_RESOURCE_NAME)

    def _gate_status_message(self) -> str:
        gate = self._custom_objects().get_namespaced_custom_object(
            CRD_GROUP, CRD_VERSION, self.argo_service.namespace, "approvalgates", PROXY_GATE_NAME)
        return (gate.get("status") or {}).get("message", "")

    def _wait_for_proxy_on_port(self, port):
        """Wait until the proxy's spec, Service and readiness all reflect the new port."""
        load_k8s_config()
        core = client.CoreV1Api()
        deadline = time.monotonic() + APPLY_TIMEOUT_SECONDS
        state = None
        while time.monotonic() < deadline:
            proxy = self._proxy()
            service = core.read_namespaced_service(PROXY_SERVICE_NAME, self.argo_service.namespace)
            service_ports = [p.port for p in service.spec.ports]
            phase = (proxy.get("status") or {}).get("phase")
            state = (proxy["spec"].get("listenPort"), service_ports, phase)
            if state[0] == port and port in service_ports and phase == "Ready" and self._port_answers(
                    PROXY_SERVICE_NAME, port):
                logger.info("Proxy is serving on %d", port)
                return
            time.sleep(10)
        raise AssertionError(f"proxy didn't move to port {port} within {APPLY_TIMEOUT_SECONDS}s; "
                             f"(spec port, service ports, phase) = {state}")

    @staticmethod
    def _port_answers(host, port) -> bool:
        try:
            with socket.create_connection((host, port), timeout=5):
                return True
        except OSError:
            return False
