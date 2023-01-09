from dataclasses import dataclass
import logging
from typing import List

from robot.api import ExecutionResult, ResultVisitor
from robot.result.model import TestCase
from robot.result import Result

from cluster_migration_core.robot_actions.action_executor import ActionExecutor


logger = logging.getLogger(__name__)


@dataclass
class ResultReport:
    passed: int
    failed: int
    skipped: int
    not_run: int

    @property
    def result_count(self) -> int:
        return sum([self.passed, self.failed, self.skipped, self.not_run])

    def __eq__(self, other):
        passed_same = self.passed == other.passed
        failed_same = self.failed == other.failed
        not_run_same = self.not_run == other.not_run
        skipped_same = self.skipped == other.skipped

        return passed_same and failed_same and not_run_same and skipped_same


class ResultReporter:
    """
    Class to encapsulate the result from the provided ActionExecutors and answer questions about what they were.

    The Robot Framework documentation for how to interact with result programmatically is fairly spotty, so it's quite
    likely there's better ways to use it, but I wasn't able to figure them out.
    """
    def __init__(self, executors: List[ActionExecutor]):
        output_paths = [str(e.output_xml_path.absolute()) for e in executors]
        logger.debug(f"Generating ResultReporter based on output.xml files: {output_paths}")
        self._result: Result = ExecutionResult(*output_paths)

    def get_result_for_tag(self, tag: str) -> ResultReport:
        """
        Find the result aggregated by tests for a single, specific tag (*not* a combo of tags conjoined w/ 'AND')
        """

        class GatherTestInfo(ResultVisitor):
            """
            "Visitor" class used to traverse the results on a particular axis.  In this case, we're traversing the
            results by test-case (rather than something like suite), so each TestCase executed will be visited by this
            code.
            
            The API here is both sophisticated and poorly documented.  A couple helpful links are supplied below for
            further reading.

            See: https://robot-framework.readthedocs.io/en/latest/autodoc/robot.result.html
            See: https://robot-framework.readthedocs.io/en/latest/autodoc/robot.result.html#module-robot.result.visitor
            """
            def __init__(self, tag: str, result_report: ResultReport, *args, **kwargs):
                self.tag = tag
                self.result_report = result_report
                super().__init__(*args, **kwargs)

            def visit_test(self, test: TestCase):
                if self.tag in test.tags:
                    if test.not_run:
                        result_report.not_run += 1
                    elif test.skipped:
                        result_report.skipped += 1
                    elif test.passed:
                        result_report.passed += 1
                    else:
                        result_report.failed += 1

        logger.debug(f"Traversing results to generate report for tag {tag}...")
        result_report = ResultReport(0, 0, 0, 0)  # Container to store results via pass-by-reference
        self._result.visit(GatherTestInfo(tag, result_report))
        logger.debug(f"Results for tag {tag}: {str(result_report)}")

        return result_report
