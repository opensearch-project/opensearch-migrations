from upgrade_testing_framework.clients.rest_client_default import RESTClientDefault
from upgrade_testing_framework.core.framework_step import FrameworkStep


class TestTargetCluster(FrameworkStep):
    """
    This step is where you run tests on the target cluster.  The code currently in here is for demo purposes only, and
    will be deleted once we've incorporated the Robot tests into the UTF.
    """

    def _run(self):
        # Get the state we need
        target_cluster = self.state.target_cluster

        # Begin the step body
        port = target_cluster.rest_ports[0]
        rest_client = RESTClientDefault()

        response_status = rest_client.get_nodes_status(port)
        self.logger.info(response_status.response_text)
        
        # Update our state
        # N/A
        