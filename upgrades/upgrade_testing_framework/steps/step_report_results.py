import xml.etree.ElementTree as ET
from typing import Dict, List
from pathlib import Path
from itertools import groupby
import textwrap

from upgrade_testing_framework.core.framework_step import FrameworkStep
from upgrade_testing_framework.core.expectation import load_knowledge_base


class ReportResults(FrameworkStep):
    """
    This step reports on the results of each expectation.
    """

    def _run(self):
        # Get the state we need
        eligible_expectation_ids = self.state.eligible_expectations
        test_output_directory = Path(self.state.get_key('test_results_directory'))

        # Begin the step body
        path_to_knowledge_base = Path("../knowledge_base")
        knowledge_base = load_knowledge_base(path_to_knowledge_base)
        expectations = {e.id:e for e in knowledge_base if e.id in eligible_expectation_ids}
        
        test_results = parse_robot_results(test_output_directory, eligible_expectation_ids)
        # These three lists will collect tuples of (expectation_id, test_phase). If the expectation was skipped
        # in all phases, test_phase is be None.
        passed_expectations: List[tuple] = []
        skipped_expectations: List[tuple] = []
        failed_expectations: List[tuple] = []

        for expectation_id, results_by_test_phase in test_results.items():
            if len(results_by_test_phase) == 0: # No results for any stage were found
                skipped_expectations.append((expectation_id, None))
                continue
            for test_phase, results in results_by_test_phase.items():
                if results['fail'] > 0:
                    failed_expectations.append((expectation_id, test_phase))
                    continue
                if results['skip'] > 0:
                    skipped_expectations.append((expectation_id, test_phase))
                    continue
                if results['pass'] > 0:
                    passed_expectations.append((expectation_id, test_phase))
                    continue
        log_results(self.logger, passed_expectations, skipped_expectations, failed_expectations, expectations) 
            
        # Update our state
        # N/A

    
def parse_robot_results(test_output_dir: Path, expectation_ids = List[str]) -> Dict[str, Dict]:
    results = {e: {} for e in expectation_ids}
    for test_dir in test_output_dir.iterdir():
        if not test_dir.is_dir():
            continue
        output_file = test_dir / "output.xml"
        if not output_file.exists():
            continue
        test_phase_name = test_dir.parts[-1]

        with output_file.open('r') as f:
            tree = ET.parse(f)

        root = tree.getroot()
        stats_by_tag = {tag.text: {k: int(v) for k, v in tag.items()} for tag in root.find('statistics/tag')}

        for expectation_id in expectation_ids:
            if expectation_id in stats_by_tag:
                results[expectation_id][test_phase_name] = stats_by_tag[expectation_id]
    return results


def log_result(logger, expectation_id, phases, expectation_description=None):
        logger.info(expectation_id)
        if not None in phases:
            logger.info(f"\tPhases: {', '.join(phases)}")
        if expectation_description:
            for line in textwrap.wrap(expectation_description, width=120, initial_indent="\tDescription: ", subsequent_indent="\t"):
                logger.info(line)
        logger.info("\n")
    

def log_results(logger, passed: List[tuple], skipped: List[tuple], failed: List[tuple], expectations: Dict):
    if len(failed) > 0:
        logger.info('======================== FAILED EXPECTATIONS ========================')
    for expectation_id, expectation_phase_pairs in groupby(failed, key=lambda x: x[0]):
        log_result(logger, expectation_id, [phase for _, phase in expectation_phase_pairs], expectations[expectation_id].description)

    if len(skipped) > 0:
        logger.info('======================== SKIPPED EXPECTATIONS =======================')
    for expectation_id, expectation_phase_pairs in groupby(skipped, key=lambda x: x[0]):
        log_result(logger, expectation_id, [phase for _, phase in expectation_phase_pairs], expectations[expectation_id].description)

    if len(passed) > 0:
        logger.info('======================== PASSED EXPECTATIONS ========================')
    for expectation_id, expectation_phase_pairs in groupby(passed, key=lambda x: x[0]):
        log_result(logger, expectation_id, [phase for _, phase in expectation_phase_pairs])
