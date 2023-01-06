from dataclasses import dataclass
from typing import List

from robot.api import ExecutionResult, ResultVisitor
from robot.result.model import TestCase
from robot.result import Result

from cluster_migration_core.robot_actions.action_executor import ActionExecutor


@dataclass
class ResultReport:
    passed: int
    failed: int
    skipped: int
    not_run: int

    @property
    def result_count(self):
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
        self._result: Result = ExecutionResult(*output_paths)

    def get_result_for_tag(self, tag: str):
        """
        Find the result aggregated by tests for a single, specific tag (*not* a combo of tags conjoined w/ 'AND')
        """

        class GatherTestInfo(ResultVisitor):
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

        result_report = ResultReport(0, 0, 0, 0)  # Container to store results via pass-by-reference
        self._result.visit(GatherTestInfo(tag, result_report))

        return result_report
