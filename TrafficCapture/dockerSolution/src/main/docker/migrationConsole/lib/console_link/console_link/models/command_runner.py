import subprocess
import os
import logging
import sys
from typing import Any, Dict, List, Optional

from console_link.models.command_result import CommandResult

logger = logging.getLogger(__name__)

FlagOnlyArgument = None


class CommandRunner:
    def __init__(self, command_root: str, command_args: Dict[str, Any], sensitive_fields: Optional[List[str]] = None,
                 run_as_detatched: bool = False, log_file: Optional[str] = None):
        self.command_args = command_args
        self.command = [command_root]
        for key, value in command_args.items():
            self.command.append(key)
            if value is not FlagOnlyArgument:
                if type(value) is not str:
                    value = str(value)
                self.command.append(value)

        self.sensitive_fields = sensitive_fields
        self.run_as_detached = run_as_detatched
        self.log_file = log_file

    def run(self, print_to_console=True) -> CommandResult:
        if self.run_as_detached:
            return self._run_as_detached_process(self.log_file)
        return self._run_as_synchronous_process(print_to_console=print_to_console)

    def sanitized_command(self) -> List[str]:
        if not self.sensitive_fields:
            return self.command
        display_command = self.command.copy()
        for field in self.sensitive_fields:
            if field in display_command:
                field_index = display_command.index(field)
                if len(display_command) > (field_index + 1) and \
                        display_command[field_index + 1] == self.command_args[field]:
                    display_command[field_index + 1] = "*" * 8
        return display_command

    def _run_as_synchronous_process(self, print_to_console: bool) -> CommandResult:
        try:
            cmd_output = subprocess.run(self.command,
                                        stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE,
                                        text=True,
                                        check=True)
            if print_to_console:
                if cmd_output.stdout:
                    sys.stdout.write(cmd_output.stdout)
                if cmd_output.stderr:
                    sys.stderr.write(cmd_output.stderr)
            return CommandResult(success=True, value="Command executed successfully", output=cmd_output)
        except subprocess.CalledProcessError as e:
            raise CommandRunnerError(e.returncode, self.sanitized_command(), e.stderr, self)

    def _run_as_detached_process(self, log_file: str) -> CommandResult:
        try:
            with open(log_file, "w") as f:
                # Start the process in detached mode
                process = subprocess.Popen(self.command, stdout=f, stderr=subprocess.STDOUT, preexec_fn=os.setpgrp)
                logger.info(f"Process started with PID {process.pid}")
                logger.info(f"Process logs available at {log_file}")
                return CommandResult(success=True, value=f"Process started with PID {process.pid}\n"
                                     f"Logs are being written to {log_file}")
        except subprocess.CalledProcessError as e:
            raise CommandRunnerError(e.returncode, self.sanitized_command(), e.stderr, self)


class CommandRunnerError(subprocess.CalledProcessError):
    def __init__(self, returncode, cmd, output=None, stderr=None):
        super().__init__(returncode, cmd, output=output, stderr=stderr)
