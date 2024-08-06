import subprocess

from console_link.models.command_runner import CommandRunner, CommandRunnerError, FlagOnlyArgument
import pytest


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
