"""E2E test for inline clientAuth PEM cert handling (opensearch-migrations#3108).

A PEM certificate provided via tls.clientAuth.trustedClientCaPem must survive the
deployProxyWithTls Argo resource step and arrive at the running capture-proxy
container intact. A PEM contains newlines and the line "-----END CERTIFICATE-----"
(which has the YAML document separator "---"); the manifest renderer now emits the
env var as an unquoted {{=toJSON(...)}}, so Argo's runtime substitution + kubectl
apply unescape it back to the EXACT original PEM with no consumer-side decode.

The test reuses the parameterized CDC workflow templates (cdc-overlay's
clientAuthPemBase64 input), brings the capture proxy up, prints the proxy pod
definition for inline inspection, and asserts the env var equals the original PEM.

Test ID: 0035 (CDC range 0031-0039)
"""
import base64
import logging

from .cdc_base import (
    MATestBase,
    MigrationType,
    MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS,
    PROXY_LABEL_SELECTOR,
    wait_for_proxy_ready,
    wait_for_replayer_consuming,
    load_k8s_config,
)

from kubernetes import client

logger = logging.getLogger(__name__)

PEM_ENV_VAR = "CAPTURE_PROXY_SSL_TRUST_CERT_PEM"

# A real, valid self-signed CA certificate (openssl req -x509, CN=migrations-test-client-ca,
# 100-year expiry). It must be a parseable X.509 cert because the capture proxy loads it as a
# trust store on startup — a structurally-invalid PEM would crash the proxy. Its embedded
# newlines and the "-----END CERTIFICATE-----" line are exactly what broke YAML parsing in #3108.
TEST_PEM_CERT = (
    "-----BEGIN CERTIFICATE-----\n"
    "MIIDKzCCAhOgAwIBAgIUEhgPMCTlcEJjJzmaPahjauT/bHwwDQYJKoZIhvcNAQEL\n"
    "BQAwJDEiMCAGA1UEAwwZbWlncmF0aW9ucy10ZXN0LWNsaWVudC1jYTAgFw0yNjA2\n"
    "MTkxNjM3NTRaGA8yMTI2MDUyNjE2Mzc1NFowJDEiMCAGA1UEAwwZbWlncmF0aW9u\n"
    "cy10ZXN0LWNsaWVudC1jYTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEB\n"
    "AL+IBN/vIuzcWeD9VreY3zSuMleTQ5yOI+x42UIIqHdtjlTwTow38Xn+rVfv0XJV\n"
    "J+Oke9hjkT0TF6wBlQMbaK32mEcAWAf5MR+uJfoYx1cfTqygyV+L9rEzHQoDjktn\n"
    "6ka3uKgHC7inUkSjzFjnEoh8PTb0/N85DqCOj9bqsvHL6w7maIbyd7n3qW0N5+YE\n"
    "C+Y36wJB1/R+HR42BSkIXFj8XWSldeqc2IWHVlW+rlrG8bRfP4UPXgzSEmt8GUUl\n"
    "yLKCRpO3LtGyuV3vizP74slVSe/d3ROeR2bmeEScoUQZm2P8q3qpggqC60z07scp\n"
    "Jk5J8D4dBM6NMuOJH74RSysCAwEAAaNTMFEwHQYDVR0OBBYEFBv1aV2cPiCAmC2d\n"
    "lSgFGwZTIVKDMB8GA1UdIwQYMBaAFBv1aV2cPiCAmC2dlSgFGwZTIVKDMA8GA1Ud\n"
    "EwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAEmPz9g9KwUFqxYXzYgr/G3h\n"
    "0kNQX4/tihf+oxeTOOohoE1xCPu12hgRwOI7e4jWkfIp6jf13vItUN2uelFidO+I\n"
    "ZgQCCEk+h+kzHQQBtl8+mk6pmeuYMolBMDOdh8k9ARfku/NokAOrl6ecALgVfwkB\n"
    "KLgwovd7oEHh7IkslwaHzZAphxdiIdo9e6TyIlMcgwN0tDf1JeNgHS1DGWO+zvuo\n"
    "aFJqcpJ3A5hcXHerozAjjp0dFTRCVMN4GyzEApUxweza/FkaXVB9u54Y0ha44SVA\n"
    "36MTX/5qnDtoXb6vWcOVnSEJzSsof5dh5g5+/W6uybaj4yvSLAT78QYol2jT4Dk=\n"
    "-----END CERTIFICATE-----\n"
)


class Test0035CdcClientAuthPemEnvVar(MATestBase):
    """Verify an inline clientAuth PEM reaches the capture-proxy env var intact (#3108).

    Brings the capture proxy up via the CDC workflow with proxyConfig.tls.clientAuth.
    trustedClientCaPem set (required:false, so the proxy is Ready without clients
    presenting certs), then asserts the proxy pod's env var equals the original PEM.
    Works in both environments like Test0040: with-clusters (k8s-local) suspends at
    pause-for-test-data; imported-clusters (EKS) submits the proxy immediately.
    """
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Verify inline clientAuth PEM arrives at the capture proxy intact (#3108).",
            migrations_required=[MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = (
            "cdc-full-e2e-imported-clusters" if self.imported_clusters
            else "cdc-e2e-migration-with-clusters"
        )
        self.parameters["pre-snapshot-proxy-submit"] = "true"
        self.parameters["capture-proxy-service-type"] = self.capture_proxy_service_type
        # base64 so the PEM's newlines survive Argo's substitution into the overlay's
        # build script; the overlay decodes it into the (unencoded) trustedClientCaPem.
        self.parameters["client-auth-pem-base64"] = base64.b64encode(
            TEST_PEM_CERT.encode("utf-8")
        ).decode("ascii")

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        ns = self.argo_service.namespace

        # with-clusters suspends at pause-for-test-data so the framework can collect
        # cluster configs; resume past it to deploy Kafka + the capture proxy.
        # imported-clusters starts the proxy-only submit immediately.
        if not self.imported_clusters:
            logger.info("Resuming workflow past pause-for-test-data to deploy the capture proxy...")
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)

        logger.info("Waiting for capture-proxy to be ready (deployProxyWithTls must apply cleanly)...")
        wait_for_proxy_ready(ns, timeout_seconds)

        # The whole point of the test: the inline clientAuth PEM survived deployProxyWithTls
        # and reached the running proxy intact.
        self._assert_pem_env_var(ns)

        # Drive the workflow to a terminal phase like Test0040, so workflow_finish()'s single
        # resume reaches the ending phase. with-clusters submits the full migration after a
        # second suspend; imported-clusters' proxy-only submit needs no further synchronisation.
        if not self.imported_clusters:
            logger.info("Waiting for pause-for-pre-snapshot-data suspend...")
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=600)
            logger.info("Resuming to submit full migration...")
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)
            wait_for_replayer_consuming(namespace=ns, workflow_name=self.workflow_name)
            logger.info("Waiting for pause-for-migration-verification suspend...")
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=600)

    def _assert_pem_env_var(self, namespace: str):
        """Print the proxy pod definition and assert its PEM env var is the raw PEM.

        Reads the env var from the pod's container spec via the K8s API rather than
        `kubectl exec` — the migration-console service account cannot exec into pods, and
        the spec value is exactly the manifest scalar #3108 is about: the PEM after Argo
        substitution + kubectl apply.
        """
        load_k8s_config()
        v1 = client.CoreV1Api()

        pods = v1.list_namespaced_pod(namespace, label_selector=PROXY_LABEL_SELECTOR)
        assert len(pods.items) > 0, "No capture proxy pod found"
        pod = pods.items[0]

        # Print the full proxy pod definition so the env var (and clientAuth wiring) is
        # visible inline in the test logs for inspection.
        logger.info("Capture proxy pod definition (%s):\n%s", pod.metadata.name, pod)

        env_value = next(
            (e.value for c in pod.spec.containers for e in (c.env or []) if e.name == PEM_ENV_VAR),
            None,
        )
        assert env_value is not None, f"{PEM_ENV_VAR} not found in proxy pod env"

        # Transparent: the env var is the RAW PEM, not base64 — the #3108 fix. Normalize
        # trailing newlines; the internal newlines and the "---" in "-----END CERTIFICATE-----"
        # (what #3108 was about) are compared exactly.
        assert env_value.rstrip("\n") == TEST_PEM_CERT.rstrip("\n"), (
            "Proxy env var does not match the original PEM after the manifest round-trip.\n"
            f"Expected: {TEST_PEM_CERT!r}\nGot:      {env_value!r}"
        )
        logger.info("SUCCESS: inline clientAuth PEM arrived at the proxy intact, no decode needed")

    def post_migration_actions(self):
        pass

    def verify_clusters(self):
        pass

    def test_after(self):
        pass
