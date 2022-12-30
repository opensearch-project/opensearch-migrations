from upgrade_testing_framework.core.framework_step import FrameworkStep


class SelectExpectations(FrameworkStep):
    """
    This step is where we collect applicable expectations based on our cluster details
    """

    def _run(self):
        # Get the state we need

        # Begin the step body
        # Query the expectation knowledge base
        self.logger.info("Querying applicable expectations...")

        # Update our state
        # TODO Temporary measure until this step queries for expectations
        self.state.eligible_expectations = ['consistent-document-count']
