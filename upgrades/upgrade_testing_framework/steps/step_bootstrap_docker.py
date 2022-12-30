from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.cluster_management.docker_framework_client as dfc


class BootstrapDocker(FrameworkStep):
    """
    This step ensures that the user's Docker install is working and builds any Dockerfiles they have supplied.
    """

    def _run(self):
        # Get the state we need
        source_docker_image = self.state.test_config.clusters_def.source.image
        target_docker_image = self.state.test_config.clusters_def.target.image

        # Begin the step body
        # We'll start by trying to make a Docker client to see if the user's setup is correct
        self.logger.info("Checking if Docker is installed and available...")
        try:
            docker_client = dfc.DockerFrameworkClient()
        except dfc.DockerNotInPathException:
            self.logger.warn("Docker is either not installed on your machine or is not in your system PATH.  Please"
                             " refer to the installation guide appropriate for your system for how to configure Docker."
                             )
            self.fail("Docker is not installed on the user's machine")
        except dfc.DockerNotResponsiveException as exception:
            self.logger.warn("Docker appears to be installed on your machine but the Docker Server is not responding"
                             " as expected.  Please refer to the installation guide appropriate for your system for how"
                             " to configure Docker.")
            self.logger.warn(f"The error message we saw was: {str(exception.original_exception)}")
            self.fail("Docker server was not responsive")
        self.logger.info("Docker appears to be installed and available")

        # Ensure Docker images are available (or die trying)
        for image in [source_docker_image, target_docker_image]:
            self.logger.info(f"Ensuring the Docker image {image} is available either locally or remotely...")
            self._ensure_image_available(docker_client, image)
            self.logger.info(f"Docker image {image} is available")

        # This is where we will later build our Dockerfiles into local images, if the user supplies one
        # However - we'll tackle that later

        # Update our state
        self.state.docker_client = docker_client

    def _ensure_image_available(self, docker_client: dfc.DockerFrameworkClient, image: str):
        """
        Check if the supplied image is available locally; try to pull it from remote repos if it isn't.
        """
        if not docker_client.is_image_available_locally(image):
            try:
                self.logger.info(f"Image {image} not available locally, pulling from remote repo...")
                docker_client.pull_image(image)
                self.logger.info(f"Pulled image {image} successfully")
            except dfc.DockerImageUnavailableException as exception:
                self.logger.warn(f"Your Docker image {image} was not available.  Ensure you spelled it"
                                 " correctly, have the require access to the remote repository, etc...")
                self.fail(f"Docker image {image} unavailable", exception)
