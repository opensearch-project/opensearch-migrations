import pytest
import unittest.mock as mock

import upgrade_testing_framework.core.shell_interactions as shell

class EndTestExpectedException(Exception):
    pass

class EndTestUnxpectedException(Exception):
    pass

class MockPexpectProcess(mock.Mock):
        def __init__(self, *args, before_values=[], exit_status=0, expect_values=[], **kwargs):
            super().__init__()
            self._expect_calls = 0
            self._before_values = before_values
            self.exitstatus = exit_status
            self._expect_values = expect_values

        def expect(self, *arg, **kwargs):
            if self._expect_calls >= len(self._expect_values):
                raise EndTestUnxpectedException()

            self.before = self._before_values[self._expect_calls]
            return_value = self._expect_values[self._expect_calls]
            self._expect_calls += 1
            return return_value

@mock.patch('upgrade_testing_framework.core.shell_interactions.pexpect')
def test_WHEN_call_shell_command_called_THEN_process_spawned_as_expected(mock_pexpect):
    # Set up our mock
    mock_pexpect.spawn.side_effect = EndTestExpectedException()

    # Run our test
    with pytest.raises(EndTestExpectedException):
        shell.call_shell_command('test command')

    # Check our results
    expected_calls = [mock.call('test command', cwd=None, env=None, timeout=None)]
    assert expected_calls == mock_pexpect.spawn.call_args_list

@mock.patch('upgrade_testing_framework.core.shell_interactions.pexpect')
def test_WHEN_call_shell_command_called_AND_cwd_provided_AND_env_provided_THEN_process_spawned_as_expected(mock_pexpect):
    # Set up our mock
    mock_pexpect.spawn.side_effect = EndTestExpectedException()

    # Run our test
    with pytest.raises(EndTestExpectedException):
        shell.call_shell_command('test command', cwd='cwd', env='env')

    # Check our results
    expected_calls = [mock.call('test command', cwd='cwd', env='env', timeout=None)]
    assert expected_calls == mock_pexpect.spawn.call_args_list

@mock.patch('upgrade_testing_framework.core.shell_interactions.pexpect')
def test_WHEN_call_shell_command_called_AND_I_want_100_percent_coverage_THEN_I_do_this(mock_pexpect):
    # Set up our mock
    mock_pexpect.spawn.side_effect = EndTestExpectedException()

    # Run our test
    with pytest.raises(EndTestExpectedException):
        shell.call_shell_command('test command', suppress_stdout=True)

    # Check our results
    expected_calls = [mock.call('test command', cwd=None, env=None, timeout=None)]
    assert expected_calls == mock_pexpect.spawn.call_args_list

@mock.patch('upgrade_testing_framework.core.shell_interactions.pexpect')
def test_WHEN_call_shell_command_called_THEN_returns_expected_values(mock_pexpect):
    # Set up our mock
    mock_process = MockPexpectProcess(before_values = [b'Feanor\n', b'Fingolfin\n', b'Finarfin'], expect_values = [0, 0, 1])
    mock_pexpect.spawn.return_value = mock_process

    # Run our test
    actual_exit_code, actual_std_out = shell.call_shell_command('test command', cwd='cwd', env='env')

    # Check our results
    expected_exit_code = 0
    expected_std_out = ['Feanor', 'Fingolfin', 'Finarfin']

    assert expected_exit_code == actual_exit_code
    assert expected_std_out == actual_std_out
    assert mock_process.close.called

@mock.patch('upgrade_testing_framework.core.shell_interactions.pexpect')
def test_WHEN_call_shell_command_called_AND_request_response_pairs_provided_THEN_returns_expected_values_AND_sends_values(mock_pexpect):
    # Test values
    request_response_pairs = [('Feanor\n', '1'), ('Fingolfin\n', '2')]

    # Set up our mock
    mock_process = MockPexpectProcess(before_values = [b'Feanor\n', b'Fingolfin\n', b'Finarfin'], expect_values = [0, 1, 3])
    mock_pexpect.spawn.return_value = mock_process

    # Run our test
    actual_exit_code, actual_std_out = shell.call_shell_command('test command', cwd='cwd', env='env', request_response_pairs=request_response_pairs)

    # Check our results
    expected_exit_code = 0
    expected_std_out = ['Feanor', 'Fingolfin', 'Finarfin']
    expected_send_values = [mock.call('1'), mock.call('2')]

    assert expected_exit_code == actual_exit_code
    assert expected_std_out == actual_std_out
    assert expected_send_values == mock_process.sendline.call_args_list
    assert mock_process.close.called

def test_WHEN_remove_ansi_codes_THEN_removes_them():
    # Run our test
    actual_result = shell._remove_ansi_codes('Hello \x1bWorld[32m![0m')

    # Check our results
    expected_result = 'Hello World!'

    assert expected_result == actual_result

def test_WHEN_remove_ansi_escape_sequences_called_THEN_returns_expected_value():
    # Test values
    test_string = '\x1b[32mNargothrond\x1b[0m'

    # Run our test
    actual_value = shell.remove_ansi_escape_sequences(test_string)

    # Check our results
    expected_value = 'Nargothrond'

    assert expected_value == actual_value