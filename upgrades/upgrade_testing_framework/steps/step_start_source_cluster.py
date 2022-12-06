from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.cluster_management.docker_framework_client as dfc

class StartSourceCluster(FrameworkStep):
    """
    This step starts the source cluster and ensures it is healthy
    """

    def _run(self):
        # Get the state we need
        # source_docker_image = self._get_state_value("source_docker_image") # Exception will be thrown if not available

        # Begin the step body
        self.logger.info("Do work")
        
        # Update our state
        