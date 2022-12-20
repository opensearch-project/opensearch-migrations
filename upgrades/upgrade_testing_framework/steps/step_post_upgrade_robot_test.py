from upgrade_testing_framework.core.framework_step import FrameworkStep
from robot_tests.test_executor import TestExecutor

class PostUpgradeRobotTest(FrameworkStep):
    """
    This step is where you run post-upgrade tests
    """

    def _run(self):
        # Get the state we need
        target_cluster = self.state.target_cluster

        # Pull host and credentials (if security is enabled) from state
        test_executor = TestExecutor("localhost", target_cluster.rest_ports[0])

        # Begin the step body
        test_executor.execute_tests(include_tags=self.state.eligible_expectations,
                                    exclude_tags="pre-upgrade",
                                    output_dir="robot_results/post_upgrade")

        # Update our state
        # N/A
