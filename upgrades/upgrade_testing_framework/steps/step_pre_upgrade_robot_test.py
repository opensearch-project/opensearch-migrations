from upgrade_testing_framework.core.framework_step import FrameworkStep
from robot_tests.test_executor import TestExecutor

class PreUpgradeRobotTest(FrameworkStep):
    """
    This step is where you run pre-upgrade setup and tests
    """

    def _run(self):
        # Get the state we need
        source_cluster = self.state.source_cluster

        # Pull host and credentials (if security is enabled) from state
        test_executor = TestExecutor("localhost", source_cluster.rest_ports[0])

        # Begin the step body
        test_executor.execute_tests(include_tags=self.state.eligible_expectations,
                                    exclude_tags="post-upgrade",
                                    output_dir="robot_results/pre_upgrade")

        # Update our state
        # N/A
