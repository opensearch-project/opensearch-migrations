import json
from typing import List

from cluster_migration_core.core.framework_step import FrameworkStep
from cluster_migration_core.robot_actions.result_reporter import ResultReporter


class ReportResults(FrameworkStep):
    """
    This step reports on the results of each expectation.
    """

    def _run(self):
        # Get the state we need
        eligible_expectations = self.state.eligible_expectations
        pre_upgrade_actions = self.state.pre_upgrade_actions
        post_upgrade_actions = self.state.post_upgrade_actions

        # Begin the step body
        passing_expectations: List[str] = []  # Had tests and all of them passed
        failing_expectations: List[str] = []  # Had tests but at least one failed
        untested_expectations: List[str] = []  # Had no passing or failing tests

        reporter = ResultReporter([pre_upgrade_actions, post_upgrade_actions])
        for expectation_id in [e.id for e in eligible_expectations]:
            result = reporter.get_result_for_tag(f"expectation::{expectation_id}")
            self.logger.debug(f"Expectation: {expectation_id}, Test Results: {str(result)}")

            if sum([result.passed, result.failed]) <= 0:
                # Covers the scenario where either there is no implementing test or they were counted as
                # skipped/not_run by the Robot Framework
                untested_expectations.append(expectation_id)
            elif result.failed > 0:
                failing_expectations.append(expectation_id)
            else:
                passing_expectations.append(expectation_id)

        self._log_results(passing_expectations, failing_expectations, untested_expectations)

        readme_url = \
            "https://github.com/opensearch-project/opensearch-migrations/blob/main/experimental/upgrades/README.md"
        help_blurb = ("For more information about how to interpret these results, please consult the Upgrade Testing"
                      f" Framework's README file: {readme_url}")
        self.logger.info(help_blurb)

        kb_url = \
            "https://github.com/opensearch-project/opensearch-migrations/tree/main/experimental/knowledge_base"
        kb_blurb = (f"You can find the expectation definitions here: {kb_url}")
        self.logger.info(kb_blurb)

        # Update our state
        # N/A

    def _log_results(self, passing_expectations: List[str], failing_expectations: List[str],
                     untested_expectations: List[str], title_len_chars: int = 100):
        # Build our title first
        title = "FINAL RESULTS"
        padding_char = "="
        padding_chars_per_side = int((title_len_chars - len(title)) / 2 - 1)  # one space per side too
        padding_per_side = padding_char * padding_chars_per_side
        title = f"\n{padding_per_side} {title} {padding_per_side}"  # Like: "====== TITLE ======"

        # Assemble the results
        final_results = {
            "passing_expectations": passing_expectations,
            "failing_expectations": failing_expectations,
            "untested_expectations": untested_expectations
        }
        final_results_str = json.dumps(final_results, sort_keys=True, indent=4)

        # Log
        results_combined = f"{title}\n{final_results_str}"
        self.logger.info(results_combined)
