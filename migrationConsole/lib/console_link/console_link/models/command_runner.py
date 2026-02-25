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
                if not isinstance(value, str):
                    value = str(value)
                self.command.append(value)

        self.sensitive_fields = sensitive_fields
        self.run_as_detached = run_as_detatched
        self.log_file = log_file

    def run(self, print_to_console=True, print_on_error=False, stream_output=False) -> CommandResult:
        if self.run_as_detached:
            if self.log_file is None:
                raise ValueError("log_file must be provided for detached mode")
            return self._run_as_detached_process(self.log_file)
        if stream_output:
            return self._run_as_streaming_process(print_to_console=print_to_console)
        return self._run_as_synchronous_process(print_to_console=print_to_console, print_on_error=print_on_error)

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

    def print_output_if_enabled(self, holder, should_print, is_error):
        if holder.stdout:
            if should_print:
                sys.stdout.write(holder.stdout)
            log_message_out = f"\nSTDOUT ({' '.join(self.command)}):\n{holder.stdout}"
            if is_error:
                logger.info(log_message_out)
            else:
                logger.debug(log_message_out)
        if holder.stderr:
            if should_print:
                sys.stderr.write(holder.stderr)
            log_message_err = f"\nSTDERR ({' '.join(self.command)}):\n{holder.stderr}"
            if is_error:
                logger.info(log_message_err)
            else:
                logger.debug(log_message_err)

    def _run_as_synchronous_process(self, print_to_console: bool, print_on_error: bool) -> CommandResult:
        try:
            cmd_output = subprocess.run(self.command,
                                        stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE,
                                        text=True,
                                        check=True)
            self.print_output_if_enabled(holder=cmd_output, should_print=print_to_console, is_error=False)
            return CommandResult(success=True, value="Command executed successfully", output=cmd_output)
        except subprocess.CalledProcessError as e:
            self.print_output_if_enabled(holder=e, should_print=print_on_error, is_error=True)
            raise CommandRunnerError(e.returncode, self.sanitized_command(), e.stdout, e.stderr)

    def _run_as_streaming_process(self, print_to_console: bool = True) -> CommandResult:
        try:
            # Start process with pipes for real-time output
            process = subprocess.Popen(
                self.command,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,  # Line buffered
                universal_newlines=True
            )

            # Read output line by line as it comes
            if process.stdout:
                for line in iter(process.stdout.readline, ''):
                    if line:
                        if print_to_console:
                            sys.stdout.write(line)
                            sys.stdout.flush()
                        logger.debug(f"STDOUT: {line.rstrip()}")

            # Wait for process to complete
            return_code = process.wait()

            if return_code == 0:
                return CommandResult(success=True, value="Command executed successfully")
            else:
                raise CommandRunnerError(return_code, self.sanitized_command())

        except Exception as e:
            logger.error(f"Streaming command failed: {e}")
            raise CommandRunnerError(-1, self.sanitized_command(), None, str(e))

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
            raise CommandRunnerError(e.returncode, self.sanitized_command(), e.stdout, e.stderr)


class CommandRunnerError(subprocess.CalledProcessError):
    def __init__(self, returncode, cmd, output=None, stderr=None):
        super().__init__(returncode, cmd, output=output, stderr=stderr)

    def __str__(self):
        return (f"Command [{' '.join(self.cmd)}] failed with exit code {self.returncode}. For more verbose output, "
                f"repeat the console command with '-v', for example 'console -v snapshot create'")
