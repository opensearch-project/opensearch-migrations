from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.cluster_management.docker_framework_client as dfc

class BootstrapDocker(FrameworkStep):
    """
    This step ensures that the user's Docker install is working and builds any Dockerfiles they have supplied.
    """

    def _run(self):
        # Get the state we need
        # source_dockerfile_path = self._get_state_value("source_dockerfile_path") # Exception will be thrown if not available

        # Begin the step body
        # We'll start by trying to make a Docker client to see if the user's setup is correct
        self.logger.info("Checking if Docker is installed and available...")
        try:
            docker_client = dfc.DockerFrameworkClient()
        except dfc.DockerNotInPathException:
            self.logger.warn("Docker is either not installed on your machine or is not in your system PATH.  Please"
                " refer to the installation guide appropriate for your system for how to configure Docker.")
            self.fail("Docker is not installed on the user's machine")
        except dfc.DockerNotResponsiveException as exception:
            self.logger.warn("Docker appears to be installed on your machine but the Docker Server is not responding"
                " as expected.  Please refer to the installation guide appropriate for your system for how to"
                " configure Docker.")            
            self.logger.warn(f"The error message we saw was: {str(exception.original_exception)}")
            self.fail("Docker server was not responsive")
        self.logger.info("Docker appears to be installed and available")

        # Next, let's build the Docker Image
        

        
        # Update our state
        self.state.docker_client = docker_client