from dataclasses import dataclass, field
from pathlib import Path
from typing import List
import unittest.mock as mock

import cluster_migration_core.robot_actions.result_reporter as rr


@mock.patch("cluster_migration_core.robot_actions.result_reporter.ExecutionResult")
def test_WHEN_execute_called_THEN_invokes_my_implementation(mock_ER):
    # This requires a fairly elaborate mock to test properly, since we need to stub out multiple Robot Framework
    # types and methods.  You work with what you're given, and in this case that's Robot Framework...

    @dataclass
    class TestTest:
        passed: bool
        not_run: bool
        skipped: bool
        tags: List = field(default_factory=List)

    def test_visit(visitor):
        tests = [
            TestTest(passed=True, not_run=False, skipped=False, tags=["wrong"]),  # Not counted, wrong tag
            TestTest(passed=False, not_run=False, skipped=True, tags=["tag"]),  # +1 skipped
            TestTest(passed=False, not_run=True, skipped=False, tags=["tag"]),  # +1 not_run
            TestTest(passed=False, not_run=True, skipped=False, tags=["tag"]),  # +1 not_run
            TestTest(passed=False, not_run=False, skipped=False, tags=["tag"]),  # +1 failed
            TestTest(passed=True, not_run=False, skipped=False, tags=["tag"]),  # +1 passed
            TestTest(passed=True, not_run=False, skipped=False, tags=["tag"]),  # +1 passed
            TestTest(passed=True, not_run=False, skipped=False, tags=["tag"]),  # +1 passed
        ]

        for test in tests:
            visitor.visit_test(test)

    mock_result = mock.Mock()
    mock_result.visit.side_effect = test_visit
    mock_ER.return_value = mock_result

    # Test values
    output_xml_1 = Path("/path_1/output.xml")
    mock_executor_1 = mock.Mock()
    mock_executor_1.output_xml_path = output_xml_1

    output_xml_2 = Path("/path_2/output.xml")
    mock_executor_2 = mock.Mock()
    mock_executor_2.output_xml_path = output_xml_2

    # Run our test
    test_reporter = rr.ResultReporter([mock_executor_1, mock_executor_2])
    test_report = test_reporter.get_result_for_tag("tag")

    # Check the results
    expected_path_args = [mock.call(
        str(output_xml_1),
        str(output_xml_2)
    )]
    assert expected_path_args == mock_ER.call_args_list

    expected_report = rr.ResultReport(3, 1, 1, 2)
    assert expected_report == test_report
