from upgrade_testing_framework.cluster_management.cluster import Cluster
from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.core.shell_interactions as shell


class TestTargetCluster(FrameworkStep):
    """
    This step is where you run tests on the target cluster
    """

    def _run(self):
        # Get the state we need
        target_cluster = self.state.target_cluster

        # Begin the step body
        port = target_cluster.rest_ports[0]
        _, output = shell.call_shell_command(f"curl -X GET \"localhost:{port}/_cat/nodes?v=true&pretty\"")
        self.logger.info("\n".join(output))
        
        # Update our state
        # N/A
        