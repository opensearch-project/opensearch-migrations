from cluster_migration_core.core.framework_step import FrameworkStep
from robot_tests.test_executor import TestExecutor


STAGE_TAG = "post-upgrade"


class PerformPostUpgradeTest(FrameworkStep):
    """
    This step is where you run post-upgrade tests
    """

    def _run(self):
        # Get the state we need
        target_cluster = self.state.target_cluster
        port = target_cluster.rest_ports[0]
        expectations = self.state.eligible_expectations
        output_directory = f"{self.state.get_key('test_results_directory')}/{STAGE_TAG}"

        # Pull host and credentials (if security is enabled) from state
        test_executor = TestExecutor("localhost", port)

        # Begin the step body
        included_tags = [f"{id}AND{STAGE_TAG}" for id in expectations]
        test_executor.execute_tests(include_tags=included_tags,
                                    output_dir=output_directory)

        # Update our state
        # N/A
