from upgrade_testing_framework.core.framework_step import FrameworkStep
from upgrade_testing_framework.cluster_management.cluster import Cluster, ClusterNotStartedInTimeException

class StartTargetCluster(FrameworkStep):
    """
    This step starts the target cluster and ensures it is healthy
    """

    def _run(self):
        source_cluster = self.state.source_cluster
        target_cluster_config = self.state.test_config.target_cluster
        docker_client = self.state.docker_client

        # Begin the step body
        self.logger.info("Creating target cluster...")
        starting_port = max(source_cluster.rest_ports) + 1
        cluster = Cluster("target-cluster", target_cluster_config, docker_client, starting_port)
        cluster.start()

        try: 
            max_wait_sec = 30
            self.logger.info(f"Waiting up to {max_wait_sec} sec for cluster to be active...")
            cluster.wait_for_cluster_to_start_up(max_wait_time_sec=30)
        except ClusterNotStartedInTimeException as exception:
            self.fail("Cluster did not start within time limit", exception)
        self.logger.info(f"Cluster {cluster.name} is active")
        
        # Update our state
        self.state.target_cluster = cluster
        