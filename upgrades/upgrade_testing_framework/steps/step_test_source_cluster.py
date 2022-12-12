from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.core.shell_interactions as shell
from upgrade_testing_framework.cluster_management.node import Node


class TestSourceCluster(FrameworkStep):
    """
    This step is where you run tests on the source cluster
    """

    def _run(self):
        # Get the state we need
        # N/A

        # Begin the step body
        _, output = shell.call_shell_command("curl -X GET \"localhost:9200/_cat/nodes?v=true&pretty\"")
        self.logger.info("\n".join(output))
        
        # Update our state
        # N/A
        