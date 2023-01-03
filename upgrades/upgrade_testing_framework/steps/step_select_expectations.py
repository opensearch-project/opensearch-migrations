from pathlib import Path

from upgrade_testing_framework.core.framework_step import FrameworkStep
from upgrade_testing_framework.core.expectation import load_knowledge_base



class SelectExpectations(FrameworkStep):
    """
    This step is where we collect applicable expectations based on our cluster details
    """

    def _run(self):
        # Get the state we need
        source_version = self.state.test_config.clusters_def.source.engine_version
        target_version = self.state.test_config.clusters_def.target.engine_version

        path_to_knowledge_base = Path("../knowledge_base")

        # Begin the step body
        # Load all expectations
        self.logger.info("Loading Knowledge Base and filtering for relevant expectations")
        expectations = load_knowledge_base(path_to_knowledge_base)
        # Filter for relevant ones
        relevant_expectation_ids = [e.id for e in expectations
                                    if e.applies_to_version(source_version) or
                                    e.applies_to_version(target_version)]
        self.logger.info(f"Found {len(relevant_expectation_ids)} relevant expectations.")
        self.logger.debug(f"Relevant expectations: {relevant_expectation_ids}")

        # Update our state
        # TODO Temporary measure until this step queries for expectations
        self.state.eligible_expectations = relevant_expectation_ids
