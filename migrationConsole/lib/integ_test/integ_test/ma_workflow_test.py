import logging
import subprocess
import time

import pytest

from .test_cases.ma_argo_test_base import MATestBase


logger = logging.getLogger(__name__)


# Worst-case timeout for `workflow reset --all --include-proxies --delete-storage`.
# The reset pipeline serially waits up to ~120s for each Strimzi-owned resource
# (kafkatopics → kafkausers → kafkas → kafkanodepools), then waits up to 120s
# for the kafkacluster CR itself, plus deletion of capture-proxy/replayer
# children, PVC removal, and finalizer-strip retries. 300s is too tight in
# practice — runs that hit Kafka tear-down regularly trip the timeout and
# strand the CI environment in a half-cleaned state for the next test case.
_WORKFLOW_RESET_TIMEOUT_SECONDS = 900

# How long to wait for capture-proxy / replayer / Kafka pods to drain after a
# reset before we give up and proceed anyway. Bounded so a stuck namespace
# doesn't burn the whole pytest timeout silently.
_NAMESPACE_DRAIN_TIMEOUT_SECONDS = 300

# Pod label prefixes / app labels we expect to be gone after a clean reset.
# These are the workloads the migration workflow stands up. If any are still
# Running when the next test starts, the inner Argo workflow tends to get
# stuck on waitForCreation because the operator sees a half-deleted CR and
# blocks reconciliation.
_RESIDUAL_POD_SELECTORS = (
    "app=capture-proxy",
    "app=replayer",
    "strimzi.io/kind=Kafka",
)


def _stream_subprocess(cmd, *, timeout):
    """Run ``cmd`` while streaming combined stdout/stderr to the logger.

    ``subprocess.run(..., capture_output=True, timeout=...)`` discards the
    accumulated output when it raises ``TimeoutExpired``, leaving us blind to
    whatever step actually hung. Streaming line-by-line lets us preserve the
    last-known progress in the pytest log even when the command is killed.

    Returns ``(returncode, captured_text, timed_out)``. If ``timed_out`` is
    True the process was killed after ``timeout`` seconds.
    """
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    captured_lines = []
    deadline = time.monotonic() + timeout
    timed_out = False
    try:
        assert proc.stdout is not None
        for line in proc.stdout:
            line = line.rstrip("\n")
            captured_lines.append(line)
            logger.info("[reset] %s", line)
            if time.monotonic() > deadline:
                timed_out = True
                break
        if timed_out:
            proc.kill()
        rc = proc.wait(timeout=10)
    except Exception:
        proc.kill()
        proc.wait(timeout=10)
        raise
    return rc, "\n".join(captured_lines), timed_out


def _log_namespace_diagnostics(namespace: str):
    """Best-effort dump of pods/PVCs/CRDs left over in ``namespace``.

    Used after a reset timeout so the next CI run shows what was still
    alive instead of leaving a silent gap in the test log.
    """
    diag_commands = (
        ["kubectl", "-n", namespace, "get", "pods", "-o", "wide"],
        ["kubectl", "-n", namespace, "get", "pvc"],
        ["kubectl", "-n", namespace, "get",
         "kafkaclusters,captureproxies,trafficreplays,snapshotmigrations,"
         "capturedtraffics,kafkas.kafka.strimzi.io,"
         "kafkatopics.kafka.strimzi.io,kafkausers.kafka.strimzi.io,"
         "kafkanodepools.kafka.strimzi.io"],
    )
    for cmd in diag_commands:
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            if result.stdout:
                logger.warning("Diagnostic '%s' stdout:\n%s",
                               " ".join(cmd), result.stdout.rstrip())
            if result.stderr:
                logger.warning("Diagnostic '%s' stderr:\n%s",
                               " ".join(cmd), result.stderr.rstrip())
        except Exception as e:
            logger.warning("Diagnostic '%s' failed: %s", " ".join(cmd), e)


def _wait_for_namespace_drain(namespace: str = "ma",
                              timeout: int = _NAMESPACE_DRAIN_TIMEOUT_SECONDS) -> bool:
    """Wait until residual migration pods are gone from ``namespace``.

    A previous test's reset can return success while Strimzi/Helm still
    finalize child resources in the background. Submitting the next test's
    workflow during that window leads to deterministic deadlocks where the
    inner ``migration-workflow`` parks on ``waitForCreation`` because the
    operator sees a half-deleted CR. Returns True if the namespace drained
    within ``timeout``, False otherwise (test still proceeds, but with a
    warning logged so the failure mode is obvious in the log).
    """
    deadline = time.monotonic() + timeout
    last_observed = None
    while time.monotonic() < deadline:
        residual = []
        for selector in _RESIDUAL_POD_SELECTORS:
            try:
                result = subprocess.run(
                    ["kubectl", "-n", namespace, "get", "pods",
                     "-l", selector,
                     "-o", "jsonpath={.items[*].metadata.name}"],
                    capture_output=True, text=True, timeout=15
                )
            except Exception as e:
                logger.warning("Pod check for selector %r failed: %s", selector, e)
                continue
            names = [n for n in (result.stdout or "").split() if n]
            if names:
                residual.append((selector, names))
        if not residual:
            return True
        if residual != last_observed:
            preview = "; ".join(f"{sel} -> {','.join(names)}"
                                for sel, names in residual)
            logger.info("Waiting for residual pods to drain: %s", preview)
            last_observed = residual
        time.sleep(5)
    logger.warning("Namespace %r still has residual migration pods after %ds; "
                   "proceeding anyway (next test may flake)",
                   namespace, timeout)
    _log_namespace_diagnostics(namespace)
    return False


def _run_workflow_reset(namespace: str = "ma"):
    """Run 'workflow reset --all --include-proxies --delete-storage' to delete all migration CRDs.

    The --delete-storage flag ensures Kafka PVCs are cleaned up between consecutive
    test runs (e.g. --test-ids=0031,0040), preventing cluster ID conflicts.

    Output is streamed to the test log so partial progress is visible if the
    command stalls — diagnosing reset hangs from a captured pytest log was
    impossible with ``capture_output=True`` because ``TimeoutExpired`` discards
    everything that was buffered.
    """
    cmd = ["workflow", "reset", "--all", "--include-proxies", "--delete-storage", "--namespace", namespace]
    logger.info("Running workflow reset: %s", " ".join(cmd))
    try:
        rc, _, timed_out = _stream_subprocess(cmd, timeout=_WORKFLOW_RESET_TIMEOUT_SECONDS)
    except FileNotFoundError:
        logger.warning("'workflow' CLI not found on PATH, skipping CRD reset")
        return
    if timed_out:
        logger.warning("workflow reset timed out after %ds; dumping namespace state",
                       _WORKFLOW_RESET_TIMEOUT_SECONDS)
        _log_namespace_diagnostics(namespace)
    elif rc != 0:
        logger.warning("workflow reset exited with code %d", rc)


@pytest.fixture(autouse=True)
def setup_and_teardown(request, keep_workflows, test_case: MATestBase):
    #-----Setup-----
    logger.info("Performing setup...")
    # Belt-and-suspenders against bleed-through from a prior test whose
    # teardown reset timed out: don't kick off the next migration-workflow
    # until the namespace is actually drained of capture-proxy/replayer/Kafka
    # pods. Bounded by _NAMESPACE_DRAIN_TIMEOUT_SECONDS so a stuck cluster
    # still surfaces a clean test failure instead of a 60-minute hang.
    _wait_for_namespace_drain()

    #-----Execute test-----
    yield

    #-----Teardown-----
    logger.info("Performing teardown...")
    if test_case.workflow_name:
        status_result = test_case.argo_service.get_workflow_status(workflow_name=test_case.workflow_name)
        if status_result.value.get("phase", "") not in ("Succeeded", "Failed", "Error", "Stopped", "Terminated"):
            test_case.argo_service.stop_workflow(workflow_name=test_case.workflow_name)
            test_case.argo_service.wait_for_ending_phase(workflow_name=test_case.workflow_name)
        # Print workflow details and save diagnostics if test failed
        if request.node.rep_call and request.node.rep_call.failed:
            logger.info(f"Test failed - printing workflow details for {test_case.workflow_name}")
            test_case.argo_service.print_workflow_details(workflow_name=test_case.workflow_name)
            test_case.argo_service.save_namespace_diagnostics("./logs", workflow_name=test_case.workflow_name)
        if not keep_workflows:
            test_case.argo_service.delete_workflow(workflow_name=test_case.workflow_name)
    # Reset all migration CRDs before test-specific cleanup
    _run_workflow_reset()
    test_case.cleanup()


def record_test(test_case: MATestBase, record_data) -> None:
    record_data({"name": test_case.__class__.__name__, "description": test_case.description})


# The test_case parameter here is dynamically provided by the pytest_generate_tests() function in conftest.py. This
# function will add a parametrize tag on this test to provide the 'test_case' it has collected
def test_migration_assistant_workflow(record_data, keep_workflows, test_case: MATestBase):
    logger.info(f"Performing the following test case: {test_case}")
    record_test(test_case=test_case, record_data=record_data)

    # Enable for stepping through workflows with Python debugger
    #breakpoint()

    # Test lifecycle:
    #   prepare_clusters        → seed test data on source cluster
    #   workflow_start           → submit Argo workflow
    #   workflow_perform_migrations → wait for migrations to complete (or replayer ready for CDC)
    #   post_migration_actions   → hook for CDC: enable capture, send traffic through proxy
    #   verify_clusters          → assert expected docs on target
    #   test_after               → assert workflow phase (overridden by CDC to skip)
    test_case.test_before()
    test_case.import_existing_clusters()
    test_case.prepare_workflow_snapshot_and_migration_config()
    test_case.prepare_workflow_parameters(keep_workflows=keep_workflows)
    # For imported clusters, we should load test data before the workflow starts as we will not
    # set up clusters and suspend
    if test_case.imported_clusters:
        test_case.prepare_clusters()
    test_case.workflow_start()
    test_case.workflow_setup_clusters()
    if not test_case.imported_clusters:
        test_case.prepare_clusters()
    test_case.workflow_perform_migrations()
    test_case.post_migration_actions()
    test_case.display_final_cluster_state()
    test_case.verify_clusters()
    test_case.workflow_finish()
    test_case.test_after()
