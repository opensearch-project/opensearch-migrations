from cluster_migration_core.core.framework_step import FrameworkStep
from robot_tests.test_executor import TestExecutor


STAGE_TAG = "pre-upgrade"


class PerformPreUpgradeTest(FrameworkStep):
    """
    This step is where you run pre-upgrade setup and tests
    """

    def _run(self):
        # Get the state we need
        source_cluster = self.state.source_cluster
        port = source_cluster.rest_ports[0]
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
