import unittest.mock as mock
import pytest

import upgrade_testing_framework.core.exception_base as exceptions
import upgrade_testing_framework.core.framework_step as step
from upgrade_testing_framework.core.framework_state import FrameworkState

TEST_STATE_DICT = {'key': 'value'}


def test_WHEN_run_called_AND_happy_path_THEN_nothing_wacky_happens():
    # Test values
    class TestRule(step.FrameworkStep):
        def _run(self):
            pass

    test_state = FrameworkState(TEST_STATE_DICT)

    # Run our test
    TestRule(test_state).run()

    # Check the results
    assert True


@mock.patch('upgrade_testing_framework.core.framework_step.FrameworkLoggingAdapter')
def test_WHEN_fail_called_THEN_raises(mock_adapter):
    # Test values
    class TestRule(step.FrameworkStep):
        def _run(self):
            self.fail("This is a unit test")

    test_state = FrameworkState(TEST_STATE_DICT)

    # Set up our mock
    mock_logger = mock.Mock()
    mock_adapter.return_value = mock_logger

    # Run our test
    with pytest.raises(step.StepFailedException):
        TestRule(test_state).run()

    # Check the results
    assert mock_logger.error.called


@mock.patch('upgrade_testing_framework.core.framework_step.FrameworkLoggingAdapter')
def test_WHEN_keyboard_interrupt_THEN_captured(mock_adapter):
    # Test values
    class TestRule(step.FrameworkStep):
        def _run(self):
            raise KeyboardInterrupt()

    test_state = FrameworkState(TEST_STATE_DICT)

    # Set up our mock
    mock_logger = mock.Mock()
    mock_adapter.return_value = mock_logger

    # Run our test
    with pytest.raises(exceptions.UserAbortException):
        TestRule(test_state).run()

    # Check the results
    assert mock_logger.warn.called
    assert mock_logger.error.called


@mock.patch('upgrade_testing_framework.core.framework_step.FrameworkLoggingAdapter')
def test_WHEN_runtime_exception_in_step_THEN_captured(mock_adapter):
    # Test values
    class TestRule(step.FrameworkStep):
        def _run(self):
            raise RuntimeError()

    test_state = FrameworkState(TEST_STATE_DICT)

    # Set up our mock
    mock_logger = mock.Mock()
    mock_adapter.return_value = mock_logger

    # Run our test
    with pytest.raises(exceptions.RuntimeFrameworkException) as exc_info:
        TestRule(test_state).run()

    # Check the results
    assert mock_logger.error.called
    assert isinstance(exc_info.value.original_exception, RuntimeError)
