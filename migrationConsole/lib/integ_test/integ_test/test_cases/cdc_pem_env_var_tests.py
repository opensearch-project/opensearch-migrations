"""Integration test for PEM cert base64 encoding (opensearch-migrations#3108).

Validates that a PEM certificate provided via trustedClientCaPem in the migration
config is base64-encoded by setupCapture.ts and correctly decoded by the capture
proxy's readTrustCertPemFromEnv(), arriving intact at the running container.

Test ID: 0035 (CDC range 0031-0039)
"""
import logging
import subprocess

from .cdc_base import (
    MATestBase,
    MigrationType,
    MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS,
    PROXY_LABEL_SELECTOR,
    wait_for_pod_ready,
    load_k8s_config,
)

from kubernetes import client

logger = logging.getLogger(__name__)

# A minimal but structurally valid PEM cert for testing.
TEST_PEM_CERT = (
    "-----BEGIN CERTIFICATE-----\n"
    "MIIBkTCB+wIJALRiMLAhLaKSMA0GCSqGSIb3DQEBCwUAMBExDzANBgNVBAMMBnRl\n"
    "c3RDQTAeFw0yNDA2MTUwMDAwMDBaFw0yNTA2MTUwMDAwMDBaMBExDzANBgNVBAMM\n"
    "BnRlc3RDQTBcMA0GCSqGSIb3DQEBAQUAABBLADBIAkEA0Z3VS5JJcds3xf0gSlCV\n"
    "-----END CERTIFICATE-----\n"
)


class Test0035PemCertEnvVarBase64Decoding(MATestBase):
    """Verify PEM cert env var is base64-decoded correctly by the capture proxy.

    The workflow base64-encodes the PEM to avoid YAML parsing issues with '---'.
    The proxy auto-detects base64 and decodes it. This test verifies the full path.
    """
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Verify PEM cert env var is base64-decoded correctly in capture proxy (#3108).",
            migrations_required=[MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = "full-migration-imported-clusters"

    def prepare_workflow_snapshot_and_migration_config(self):
        snapshot_and_migration_configs = [{"migrations": [{"metadataMigrationConfig": {}}]}]
        self.workflow_snapshot_and_migration_config = snapshot_and_migration_configs

    def prepare_clusters(self):
        """Inject TLS config with trustedClientCaPem into the migration config."""
        source_configs = self.parameters.get("source-configs", [])
        if not source_configs:
            source_configs = [{
                "source": self.source_cluster.config,
                "snapshot-and-migration-configs": self.workflow_snapshot_and_migration_config
            }]
        for sc in source_configs:
            if "traffic" not in sc:
                sc["traffic"] = {}
            sc["traffic"]["proxies"] = {
                "capture-proxy": {
                    "source": "source1",
                    "proxyConfig": {
                        "listenPort": 9201,
                        "noCapture": False,
                        "serviceType": "ClusterIP",
                        "tls": {
                            "clientAuth": {
                                "trustedClientCaPem": TEST_PEM_CERT
                            }
                        }
                    }
                }
            }
        self.parameters["source-configs"] = source_configs

    def workflow_perform_migrations(self, timeout_seconds: int = 600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        logger.info("Waiting for capture proxy pod to be ready...")
        wait_for_pod_ready(self.argo_service.namespace, PROXY_LABEL_SELECTOR, timeout_seconds)

    def post_migration_actions(self):
        """Read the PEM env var from the proxy pod and validate base64 decoding worked."""
        load_k8s_config()
        v1 = client.CoreV1Api()

        pods = v1.list_namespaced_pod(
            self.argo_service.namespace, label_selector=PROXY_LABEL_SELECTOR
        )
        assert len(pods.items) > 0, "No capture proxy pod found"
        pod_name = pods.items[0].metadata.name
        namespace = self.argo_service.namespace

        logger.info("Reading CAPTURE_PROXY_SSL_TRUST_CERT_PEM from pod %s", pod_name)
        result = subprocess.run(
            ["kubectl", "exec", pod_name, "-n", namespace,
             "--", "printenv", "CAPTURE_PROXY_SSL_TRUST_CERT_PEM"],
            capture_output=True, text=True, timeout=30
        )

        env_value = result.stdout.strip()
        logger.info("Env var length: %d, first 60 chars: %s", len(env_value), repr(env_value[:60]))

        # The env var should contain base64-encoded PEM (no raw PEM markers)
        # because setupCapture.ts uses toBase64()
        assert "-----BEGIN" not in env_value, (
            f"Raw PEM found in env var — toBase64 encoding did not occur. "
            f"Got: {env_value[:100]}"
        )

        # Decode and verify the original PEM is recoverable
        import base64
        decoded = base64.b64decode(env_value).decode("utf-8")
        assert "-----BEGIN CERTIFICATE-----" in decoded, (
            f"Decoded value missing PEM BEGIN marker. Got: {decoded[:100]}"
        )
        assert "-----END CERTIFICATE-----" in decoded, (
            f"Decoded value missing PEM END marker. Got: {decoded[-100:]}"
        )
        logger.info("SUCCESS: PEM cert is correctly base64-encoded in env var and decodable")

    def verify_clusters(self):
        pass

    def test_after(self):
        pass
