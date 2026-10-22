import logging
import subprocess
from typing import Callable, List, Tuple

import pytest

from console_link.workflow.commands.crd_utils import list_migration_resources, resource_display_name
from console_link.workflow.models.utils import load_k8s_config

from .test_cases.ma_argo_test_base import MATestBase
from .metric_operations import assert_cloudwatch_capture_replay_metrics_for_workflow_run


logger = logging.getLogger(__name__)


def _run_workflow_reset(namespace: str = "ma"):
    """Run 'workflow reset --all --include-proxies --delete-storage' to delete all migration CRDs.

    The --delete-storage flag ensures Kafka PVCs are cleaned up between consecutive
    test runs (e.g. --test-ids=0031,0040), preventing cluster ID conflicts.
    """
    cmd = ["workflow", "reset", "--all", "--include-proxies", "--delete-storage", "--namespace", namespace]
    logger.info("Running workflow reset: %s", " ".join(cmd))
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        if result.stdout:
            logger.info("workflow reset stdout:\n%s", result.stdout)
        if result.stderr:
            logger.warning("workflow reset stderr:\n%s", result.stderr)
        if result.returncode != 0:
            pytest.fail(f"workflow reset exited with code {result.returncode}")
    except subprocess.TimeoutExpired:
        pytest.fail("workflow reset timed out after 300s")
    except FileNotFoundError:
        pytest.fail("'workflow' CLI not found on PATH; cannot reset migration CRDs")


def _fail_if_migration_resources_exist(namespace: str = "ma"):
    """Fail before a test starts if a previous case left migration resources behind."""
    try:
        load_k8s_config()
        resources = list_migration_resources(namespace)
    except Exception as e:
        pytest.fail(f"Unable to verify clean migration-resource state before test: {e}")

    if not resources:
        return

    formatted = [resource_display_name(plural, name) for plural, name, _, _ in resources]
    remaining_resources = ", ".join(formatted)
    pytest.fail(
        "Migration resources already exist before test starts; a previous workflow reset likely failed. "
        f"Remaining resources in namespace {namespace}: {remaining_resources}"
    )


@pytest.fixture(autouse=True)
def setup_and_teardown(
    request,
    keep_workflows,
    skip_workflow_reset,
    dump_all_workflow_output_artifacts,
    test_case: MATestBase,
):
    #-----Setup-----
    logger.info("Performing setup...")
    _fail_if_migration_resources_exist()

    #-----Execute test-----
    yield

    #-----Teardown-----
    logger.info("Performing teardown...")
    _perform_teardown(
        request=request,
        keep_workflows=keep_workflows,
        skip_workflow_reset=skip_workflow_reset,
        dump_all_workflow_output_artifacts=dump_all_workflow_output_artifacts,
        test_case=test_case,
    )


def _perform_teardown(request, keep_workflows, skip_workflow_reset, dump_all_workflow_output_artifacts,
                      test_case: MATestBase) -> None:
    """Dump diagnostics on failure, quiesce the workflow, then delete/reset/clean up.

    Those steps are independent, and none may preempt the others — least of all the dump. On a
    failed test the dump is the only record of *why*, and it cannot be reconstructed once the
    workflow has been stopped and the namespace reset. A workflow parked on an unexpected
    approval gate is exactly that hazard: it stays 'Running' forever, so the quiesce step's gate
    check raises, and while it ran ahead of the dump it took the dump down with it. Each step is
    now isolated, and any suppressed error is re-raised at the end so a broken teardown is still
    reported rather than silently ignored.
    """
    teardown_errors: List[Tuple[str, BaseException]] = []

    def teardown_step(description: str, step: Callable[[], None]) -> None:
        try:
            step()
        except (KeyboardInterrupt, SystemExit):
            raise
        except BaseException as e:
            # BaseException rather than Exception: pytest.fail() raises Failed, which derives
            # from BaseException, and _run_workflow_reset uses it. An interrupt still aborts.
            logger.error("Teardown step '%s' failed: %s", description, e, exc_info=True)
            teardown_errors.append((description, e))

    dumped = False

    def dump_diagnostics(reason: str) -> None:
        nonlocal dumped
        if dumped:
            return
        dumped = True
        logger.info(f"{reason} - printing workflow details for {test_case.workflow_name}")
        # Stepped individually: save_namespace_diagnostics writes the artifacts Jenkins
        # archives, so an earlier print failing must not cost us the saved copy.
        teardown_step("print workflow status", lambda: test_case.argo_service.print_workflow_status(
            workflow_name=test_case.workflow_name))
        teardown_step("print migration resource status",
                      test_case.argo_service.print_migration_resource_status)
        teardown_step("print workflow details", lambda: test_case.argo_service.print_workflow_details(
            workflow_name=test_case.workflow_name))
        teardown_step("print namespace diagnostics", lambda: test_case.argo_service.print_namespace_diagnostics(
            workflow_name=test_case.workflow_name,
            include_all_workflow_output_artifacts=dump_all_workflow_output_artifacts,
        ))
        teardown_step("save namespace diagnostics", lambda: test_case.argo_service.save_namespace_diagnostics(
            "./logs",
            workflow_name=test_case.workflow_name,
            include_all_workflow_output_artifacts=dump_all_workflow_output_artifacts,
        ))

    if test_case.workflow_name:
        # Dump BEFORE quiescing, for two reasons. Nothing upstream can then preempt it, and a
        # workflow that hasn't been stopped yet still shows the state that matters: the suspended
        # node it is parked on, the gate name, which resource's apply was denied. stop_workflow
        # tears that down. On the common failure the workflow has already reached a terminal
        # phase, so quiesce is a no-op and the ordering makes no difference either way.
        # On success the full workflow-status JSON and migration-resource YAML are just noise,
        # which is why this is conditional at all.
        if request.node.rep_call and request.node.rep_call.failed:
            dump_diagnostics("Test failed")
        teardown_step("stop workflow and wait for it to end", lambda: _quiesce_workflow(test_case))
        # A workflow that will not stop is a real defect even when every assertion passed —
        # parking on an unapprovable gate is exactly that — and teardown is the last moment
        # the namespace still holds the evidence.
        if teardown_errors:
            dump_diagnostics("Teardown failed")
        if not keep_workflows:
            teardown_step("delete workflow", lambda: test_case.argo_service.delete_workflow(
                workflow_name=test_case.workflow_name))
    # Reset all migration CRDs before test-specific cleanup unless the outer runner is preserving the run.
    if not skip_workflow_reset:
        teardown_step("reset migration resources", _run_workflow_reset)
    teardown_step("test case cleanup", test_case.cleanup)

    if teardown_errors:
        # A single error is re-raised as-is so callers and tests can still match on its type
        # (a parked gate surfaces as ParkedApprovalGateError, not a generic wrapper).
        if len(teardown_errors) == 1:
            raise teardown_errors[0][1]
        summary = "; ".join(f"{description}: {type(e).__name__}: {e}" for description, e in teardown_errors)
        raise RuntimeError(f"{len(teardown_errors)} teardown steps failed: {summary}") from teardown_errors[0][1]


def _quiesce_workflow(test_case: MATestBase) -> None:
    """Stop the workflow and wait for it to reach a terminal phase.

    Skipped when it has already ended. Raises ParkedApprovalGateError if the workflow is
    parked on an approval gate the test never intended to sit on, since it would otherwise
    never reach an ending phase at all.
    """
    status_result = test_case.argo_service.get_workflow_status(workflow_name=test_case.workflow_name)
    if status_result.value.get("phase", "") not in ("Succeeded", "Failed", "Error", "Stopped", "Terminated"):
        test_case.argo_service.stop_workflow(workflow_name=test_case.workflow_name)
        test_case.argo_service.wait_for_ending_phase(workflow_name=test_case.workflow_name)


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
    test_case.assert_observability()
    assert_cloudwatch_capture_replay_metrics_for_workflow_run(namespace=test_case.argo_service.namespace)
