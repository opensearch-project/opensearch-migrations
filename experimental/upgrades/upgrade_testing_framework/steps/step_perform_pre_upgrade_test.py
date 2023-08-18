import os
from pathlib import Path

from cluster_migration_core.core.framework_step import FrameworkStep
from cluster_migration_core.robot_actions.cluster_action_executor import ClusterActionExecutor


STAGE_TAG = "pre-upgrade"
PACKAGE_DIR = Path(os.path.dirname(os.path.abspath(__file__))).parent
TESTS_DIR = os.path.join(PACKAGE_DIR, "robot_test_defs")


class PerformPreUpgradeTest(FrameworkStep):
    """
    This step is where you run pre-upgrade setup and tests
    """

    def _run(self):
        # Get the state we need
        source_cluster = self.state.source_cluster
        node = source_cluster.nodes[0]  # shouldn't matter which node we pick
        engine_version = str(node.engine_version)
        port = source_cluster.rest_ports[0]
        eligible_expectations = self.state.eligible_expectations
        output_directory = f"{self.state.get_key('test_results_directory')}/{STAGE_TAG}"

        # Begin the step body
        path_to_actions = Path(TESTS_DIR)
        path_to_outputs = Path(output_directory)
        included_tags = [f"expectation::{e.id}ANDstage::{STAGE_TAG}" for e in eligible_expectations]
        pre_upgrade_executor = ClusterActionExecutor(
            hostname="localhost",
            port=port,
            actions_dir=path_to_actions,
            output_dir=path_to_outputs,
            include_tags=included_tags,
            engine_version=engine_version
        )
        
        pre_upgrade_executor.execute()

        # Update our state
        self.state.pre_upgrade_actions = pre_upgrade_executor
