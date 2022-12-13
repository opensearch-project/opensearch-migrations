from upgrade_testing_framework.core.framework_step import FrameworkStep
from upgrade_testing_framework.cluster_management.cluster import Cluster, ClusterNotStartedInTimeException

class StartSourceCluster(FrameworkStep):
    """
    This step starts the source cluster and ensures it is healthy
    """

    def _run(self):
        # Get the state we need
        source_docker_image = self._get_state_value("test_config")["source_docker_image"]
        docker_client = self.state.docker_client

        # Begin the step body
        self.logger.info("Creating source cluster...")
        cluster = Cluster("source-cluster", 3, docker_client, source_docker_image)
        cluster.start()

        try: 
            max_wait_sec = 30
            self.logger.info(f"Waiting up to {max_wait_sec} sec for cluster to be active...")
            cluster.wait_for_cluster_to_start_up(wait_limit_sec=30)
        except ClusterNotStartedInTimeException as exception:
            self.fail("Cluster did not start within time limit", exception)
        self.logger.info(f"Cluster {cluster.name} is active")
        
        # Update our state
        self.state.source_cluster = cluster
        