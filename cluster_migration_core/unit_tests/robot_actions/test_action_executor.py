from pathlib import Path
import pytest
import unittest.mock as mock

import cluster_migration_core.robot_actions.action_executor as ae


def test_WHEN_execute_called_THEN_invokes_my_implementation():
    # Test values
    mock_impl = mock.Mock()

    class TestExecutor(ae.ActionExecutor):
        def _execute(self):
            mock_impl()

    output_dir = Path("/")
    output_xml = Path("/output.xml")

    test_executor = TestExecutor(Path("."), output_dir)

    # Run our test
    test_executor.execute()

    # Check the results
    assert str(output_xml) == str(test_executor.output_xml_path)
    mock_impl.assert_called_once()


def test_WHEN_execute_called_AND_havent_executed_THEN_raises():
    # Test values
    class TestExecutor(ae.ActionExecutor):
        def _execute(self):
            pass

    test_executor = TestExecutor(Path("."), Path("."))

    # Run our test
    with pytest.raises(ae.ActionsUnexecutedExeception):
        test_executor.output_xml_path
