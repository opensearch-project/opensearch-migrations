import subprocess
import os
import logging
from typing import Dict, List, Optional

from console_link.models.command_result import CommandResult


logger = logging.getLogger(__name__)


class CommandRunner:
    def __init__(self, command_root: str, command_args: Dict[str, str], password_field: Optional[str] = None,
                 run_as_detatched: bool = False, log_file: Optional[str] = None):
        self.command_args = command_args
        self.command = [command_root]
        for key, value in command_args.items():
            self.command.append(key)
            if value is not None:
                self.command.append(value)
        
        self.password_field = password_field
        self.run_as_detached = run_as_detatched
        self.log_file = log_file
    
    def run(self) -> CommandResult:
        if self.run_as_detached:
            return self._run_as_detached_process(self.log_file)
        return self._run_as_synchronous_process()

    def sanitized_command(self) -> List[str]:
        if not self.password_field or self.password_field not in self.command_args:
            return self.command
        # Check whether the value of the password field is in the command
        password_index = self.command.index(self.command_args[self.password_field])
        return self.command[:password_index] + ["*" * 8] + self.command[password_index + 1:]

    def _run_as_synchronous_process(self) -> CommandResult:
        try:
            # Pass None to stdout and stderr to not capture output and show in terminal
            subprocess.run(self.command, stdout=None, stderr=None, text=True, check=True)
            return CommandResult(success=True, value="Command executed successfully")
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
