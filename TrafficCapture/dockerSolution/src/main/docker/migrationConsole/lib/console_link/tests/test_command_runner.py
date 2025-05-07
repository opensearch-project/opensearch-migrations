import pytest
import subprocess

from console_link.models.command_runner import CommandRunner, CommandRunnerError, FlagOnlyArgument


def test_command_runner_builds_command_without_args():
    runner = CommandRunner("ls", {})
    assert runner.command == ["ls"]
    assert runner.sanitized_command() == ["ls"]


def test_command_runner_builds_command_with_args():
    runner = CommandRunner("ls", {"-l": "/tmp"})
    assert runner.command == ["ls", "-l", "/tmp"]
    assert runner.sanitized_command() == ["ls", "-l", "/tmp"]


def test_command_runner_builds_command_with_multiple_args():
    runner = CommandRunner("ls", {"-l": "/tmp", "-a": "/tmp"})
    assert runner.command == ["ls", "-l", "/tmp", "-a", "/tmp"]
    assert runner.sanitized_command() == ["ls", "-l", "/tmp", "-a", "/tmp"]


def test_command_runner_sanitizes_input_with_password():
    runner = CommandRunner("ls", {"-l": "/tmp", "--password": "realpassword"}, sensitive_fields=["--password"])
    assert runner.command == ["ls", "-l", "/tmp", "--password", "realpassword"]
    assert runner.sanitized_command() == ["ls", "-l", "/tmp", "--password", "********"]


def test_command_runner_sanitizes_password_with_error(mocker):
    runner = CommandRunner("ls", {"-l": "/tmp", "--password": "realpassword"}, sensitive_fields=["--password"])
    assert runner.command == ["ls", "-l", "/tmp", "--password", "realpassword"]
    assert runner.sanitized_command() == ["ls", "-l", "/tmp", "--password", "********"]

    mocker.patch("subprocess.run", side_effect=subprocess.CalledProcessError(1, runner.command))

    with pytest.raises(CommandRunnerError) as excinfo:
        runner.run()
    actual_command = excinfo.value.args[1]
    assert "********" in actual_command
    assert "realpassword" not in actual_command


def test_command_runner_sanitizes_password_if_value_occurs_twice():
    runner = CommandRunner("command", {
        "--username": "admin",
        "--password": "admin"
    }, sensitive_fields=["--password"])
    assert runner.command == ["command", "--username", "admin", "--password", "admin"]
    assert runner.sanitized_command() == ["command", "--username", "admin", "--password", "********"]


def test_command_runner_sanitizing_a_flag_field_is_a_noop():
    runner = CommandRunner("command", {
        "--username": "admin",
        "--use-password": FlagOnlyArgument
    }, sensitive_fields=["--use-password"])
    assert runner.command == ["command", "--username", "admin", "--use-password"]
    assert runner.sanitized_command() == ["command", "--username", "admin", "--use-password"]


def test_command_runner_prints_output_as_run_default(capsys, mocker):
    mock_stdout = "Found 5 directories"
    mock_stderr = "Unknown file path"
    mock_subprocess_result = subprocess.CompletedProcess(args=[], returncode=0, stdout=mock_stdout, stderr=mock_stderr)
    runner = CommandRunner("ls", {})
    mocker.patch("subprocess.run", return_value=mock_subprocess_result)
    runner.run()
    # Capture stdout/stderr output
    captured = capsys.readouterr()
    assert captured.out == mock_stdout
    assert captured.err == mock_stderr


def test_command_runner_prints_nothing_when_output_disabled(capsys, mocker):
    mock_stdout = "Found 5 directories"
    mock_stderr = "Unknown file path"
    mock_subprocess_result = subprocess.CompletedProcess(args=[], returncode=0, stdout=mock_stdout, stderr=mock_stderr)
    runner = CommandRunner("ls", {})
    mocker.patch("subprocess.run", return_value=mock_subprocess_result)
    runner.run(print_to_console=False)
    # Capture stdout/stderr output
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == ""


def test_command_runner_handles_no_output(capsys, mocker):
    mock_subprocess_result = subprocess.CompletedProcess(args=[], returncode=0, stdout=None, stderr=None)
    runner = CommandRunner("ls", {})
    mocker.patch("subprocess.run", return_value=mock_subprocess_result)
    runner.run()
    # Capture stdout/stderr output
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == ""


def test_command_runner_prints_nothing_on_error_when_disabled(capsys, mocker):
    mock_stdout = "Found 5 directories"
    mock_stderr = "Unknown file path"
    runner = CommandRunner("ls", {})
    mocker.patch("subprocess.run", side_effect=subprocess.CalledProcessError(returncode=1, cmd=["ls"],
                                                                             output=mock_stdout, stderr=mock_stderr))
    with pytest.raises(CommandRunnerError):
        runner.run(print_on_error=False)
    # Capture stdout/stderr output
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == ""


def test_command_runner_prints_output_on_error_when_enabled(capsys, mocker):
    mock_stdout = "Found 5 directories"
    mock_stderr = "Unknown file path"
    runner = CommandRunner("ls", {})
    mocker.patch("subprocess.run", side_effect=subprocess.CalledProcessError(returncode=1, cmd=["ls"],
                                                                             output=mock_stdout, stderr=mock_stderr))
    with pytest.raises(CommandRunnerError):
        runner.run(print_on_error=True)
    # Capture stdout/stderr output
    captured = capsys.readouterr()
    assert captured.out == mock_stdout
    assert captured.err == mock_stderr


def test_command_runner_handles_no_output_on_error(capsys, mocker):
    runner = CommandRunner("ls", {})
    mocker.patch("subprocess.run", side_effect=subprocess.CalledProcessError(returncode=1, cmd=["ls"],
                                                                             output=None, stderr=None))
    with pytest.raises(CommandRunnerError):
        runner.run(print_on_error=True)
    # Capture stdout/stderr output
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == ""
