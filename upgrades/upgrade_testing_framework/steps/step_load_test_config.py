from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.core.test_config_wrangling as tcw

class LoadTestConfig(FrameworkStep):
    """
    This step confirms the test configuration is valid and loads it into the Framework's state
    """

    def _run(self):
        # Get the state we need
        test_config_path = self._get_state_value("test_config_path")

        # Begin the step body
        self.logger.info("Loading test config file...")
        try:
            test_config = tcw.load_test_config(test_config_path)
        except Exception as exception:
            self.fail(f"Unable to load test config file.  Details: {str(exception)}", exception)
        self.logger.info("Loaded test config file successfully")
        
        # Update our state
        self.state.test_config = test_config
        