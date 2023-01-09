from pathlib import Path

from cluster_migration_core.core.framework_step import FrameworkStep
from cluster_migration_core.core.expectation import load_knowledge_base


class SelectExpectations(FrameworkStep):
    """
    This step is where we collect applicable expectations based on our cluster details
    """

    def _run(self):
        # Get the state we need
        path_to_knowledge_base = Path(self.state.get_key('knowledge_base_path'))
        source_version = self.state.test_config.clusters_def.source.engine_version
        target_version = self.state.test_config.clusters_def.target.engine_version

        # Begin the step body
        # Load all expectations
        self.logger.info("Loading Knowledge Base and filtering for relevant expectations")
        expectations = load_knowledge_base(path_to_knowledge_base)
        # Filter for relevant ones
        relevant_expectations = [e for e in expectations
                                 if e.applies_to_version(source_version) or e.applies_to_version(target_version)]
        relevant_expectation_ids = [e.id for e in relevant_expectations]
        self.logger.info(f"Found {len(relevant_expectations)} relevant expectations.")
        self.logger.debug(f"Relevant expectations: {relevant_expectation_ids}")

        # Update our state
        self.state.eligible_expectations = relevant_expectations
