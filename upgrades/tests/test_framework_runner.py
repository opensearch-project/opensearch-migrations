import json
import unittest.mock as mock
import py
import pytest
import sys

import upgrade_testing_framework.core.exception_base as exceptions
import upgrade_testing_framework.core.constants as constants
from upgrade_testing_framework.core.framework_runner import FrameworkRunner
from upgrade_testing_framework.core.framework_state import FrameworkState
from upgrade_testing_framework.core.logging_wrangler import LoggingWrangler
from upgrade_testing_framework.core.workspace_wrangler import WorkspaceWrangler

call_register = mock.Mock()

class ExpectedException(Exception):
    pass

class UnexpectedException(Exception):
    pass

class MockStep(mock.Mock):
    def __init__(self, *args, **kwargs):
        super().__init__()

    def run(self):
        getattr(call_register, self.__class__.__name__)()

    @classmethod
    def cls_name(cls) -> str:
        return cls.__name__

class Step1(MockStep):
    pass

class Step2(MockStep):
    pass

class Step3(MockStep):
    pass

class StepAbortException(MockStep):
    def run(self):
        super().run()
        exception = ExpectedException('This is a test')
        raise exceptions.UserAbortException("User Abort")

class StepRaiseStepFailedException(MockStep):
    def run(self):
        super().run()
        exception = ExpectedException('This is a test')
        raise exceptions.StepFailedException("Fail", exception)

class StepRaiseRuntimeFrameworkException(MockStep):
    def run(self):
        super().run()
        exception = UnexpectedException('This is a test')
        raise exceptions.RuntimeFrameworkException(str(exception), exception)

class StepRaiseUnhandledException(MockStep):
    def run(self):
        super().run()
        raise UnexpectedException('This is a test')

@pytest.fixture
def reset_call_register():
    setattr(sys.modules[__name__], 'call_register', mock.Mock())

@pytest.fixture
def test_workspace(tmpdir):
    return WorkspaceWrangler(base_directory=tmpdir.strpath)

@pytest.fixture
def test_logging_context(test_workspace):
    return LoggingWrangler(test_workspace)

@pytest.fixture
def state_file_for_resume(test_workspace):
    state = {'current_step': 'Step2'}
    state_file = py.path.local(test_workspace.state_file)
    state_file.write(json.dumps(state, sort_keys=True, indent=4))

TEST_CONFIG_FILE_PATH = "./path/to/test_config.json"

# This patch replaces the namespace of the "steps" module with that of this current file
@mock.patch('upgrade_testing_framework.core.framework_runner.steps', sys.modules[__name__])
class TestFrameworkRunner():

    def test_WHEN_run_called_THEN_invokes_all_steps(self, reset_call_register, test_logging_context, test_workspace):
        # Test values
        step_order = [Step1, Step2, Step3]
        runner = FrameworkRunner(test_logging_context, test_workspace, mock.Mock())

        # Set up the mock
        runner.step_order = step_order

        # Run our test
        end_state = runner.run(TEST_CONFIG_FILE_PATH)

        # Check our results
        assert call_register.Step1.called
        assert call_register.Step2.called
        assert call_register.Step3.called
        assert constants.EXIT_TYPE_SUCCESS == end_state.get('exit_type')

    def test_WHEN_run_called_AND_user_abort_THEN_invokes_expected_steps(self, reset_call_register, test_logging_context, test_workspace):
        # Test values
        step_order = [Step1, StepAbortException, Step3]
        runner = FrameworkRunner(test_logging_context, test_workspace, mock.Mock())

        # Set up the mock
        runner.step_order = step_order

        # Run our test
        end_state = runner.run(TEST_CONFIG_FILE_PATH)

        # Check our results
        assert call_register.Step1.called
        assert call_register.StepAbortException.called
        assert not call_register.Step3.called
        assert constants.EXIT_TYPE_ABORT == end_state.get('exit_type')

    def test_WHEN_run_called_THEN_writes_state_file(self, reset_call_register, test_logging_context, test_workspace):
        # Test values
        step_order = [Step1, Step2, Step3]
        runner = FrameworkRunner(test_logging_context, test_workspace, mock.Mock())

        # Set up the mock
        runner.step_order = step_order

        # Run our test
        runner.run(TEST_CONFIG_FILE_PATH)

        # Check our results
        expected_contents = {
            'current_step': 'Step3',
            'exit_type': constants.EXIT_TYPE_SUCCESS,
            'log_file': runner.log_file,
            'state_file': test_workspace.state_file,
            'test_config_path': TEST_CONFIG_FILE_PATH
        }
        actual_contents = json.load(py.path.local(test_workspace.state_file))

        assert expected_contents == actual_contents
    
    def test_WHEN_run_called_AND_step_throws_exception_THEN_writes_state_file_AND_exits_normally(self, reset_call_register, test_logging_context, test_workspace):
        # Test values
        step_order = [Step1, StepRaiseRuntimeFrameworkException, Step3]
        runner = FrameworkRunner(test_logging_context, test_workspace, mock.Mock())

        # Set up the mock
        runner.step_order = step_order

        # Run our test
        end_state = runner.run(TEST_CONFIG_FILE_PATH)

        # Check our results
        expected_contents = {
            'current_step': 'StepRaiseRuntimeFrameworkException',
            'exit_type': constants.EXIT_TYPE_FAILURE_UNEXPECTED,
            'last_exception_message': 'This is a test',
            'last_exception_type': 'UnexpectedException',
            'log_file': runner.log_file,
            'state_file': test_workspace.state_file,
            'test_config_path': TEST_CONFIG_FILE_PATH
        }
        actual_contents = json.load(py.path.local(test_workspace.state_file))

        assert expected_contents == actual_contents

    def test_WHEN_run_called_AND_step_fails_THEN_writes_state_file_AND_exits_normally(self, reset_call_register, test_logging_context, test_workspace):
        # Test values
        step_order = [Step1, StepRaiseStepFailedException, Step3]
        runner = FrameworkRunner(test_logging_context, test_workspace, mock.Mock())

        # Set up the mock
        runner.step_order = step_order

        # Run our test
        end_state = runner.run(TEST_CONFIG_FILE_PATH)

        # Check our results
        expected_contents = {
            'current_step': 'StepRaiseStepFailedException',
            'exit_type': constants.EXIT_TYPE_FAILURE,
            'last_exception_message': 'This is a test',
            'last_exception_type': 'ExpectedException',
            'log_file': runner.log_file,
            'state_file': test_workspace.state_file,
            'test_config_path': TEST_CONFIG_FILE_PATH
        }
        actual_contents = json.load(py.path.local(test_workspace.state_file))

        assert expected_contents == actual_contents
    
    def test_WHEN_run_called_AND_step_throws_unhandled_exception_THEN_writes_state_file_AND_exits_normally(self, reset_call_register, test_logging_context, test_workspace):
        # Test values
        step_order = [Step1, StepRaiseUnhandledException, Step3]
        runner = FrameworkRunner(test_logging_context, test_workspace, mock.Mock())

        # Set up the mock
        runner.step_order = step_order

        # Run our test
        end_state = runner.run(TEST_CONFIG_FILE_PATH)

        # Check our results
        expected_contents = {
            'current_step': 'StepRaiseUnhandledException',
            'exit_type': constants.EXIT_TYPE_FAILURE_UNHANDLED,
            'last_exception_message': 'This is a test',
            'last_exception_type': 'UnexpectedException',
            'log_file': runner.log_file,
            'state_file': test_workspace.state_file,
            'test_config_path': TEST_CONFIG_FILE_PATH
        }
        actual_contents = json.load(py.path.local(test_workspace.state_file))

        assert expected_contents == actual_contents