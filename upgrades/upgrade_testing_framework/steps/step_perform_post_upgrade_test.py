from cluster_migration_core.core.framework_step import FrameworkStep
from robot_tests.test_executor import TestExecutor


class PerformPostUpgradeTest(FrameworkStep):
    """
    This step is where you run post-upgrade tests
    """

    def _run(self):
        # Get the state we need
        target_cluster = self.state.target_cluster
        port = target_cluster.rest_ports[0]
        expectations = self.state.eligible_expectations
        output_directory = f"{self.state.get_key('test_results_directory')}/post_upgrade"

        # Pull host and credentials (if security is enabled) from state
        test_executor = TestExecutor("localhost", port)

        # Begin the step body
        test_executor.execute_tests(include_tags=expectations,
                                    exclude_tags="pre-upgrade",
                                    output_dir=output_directory)

        # Update our state
        # N/A
