import subprocess
import os
import logging
import sys
import threading
import time
from typing import Any, Dict, List, Optional

from console_link.models.command_result import CommandResult

logger = logging.getLogger(__name__)

FlagOnlyArgument = None


class _IdleWatchdog:
    """Re-armable idle-timeout watchdog for a subprocess streaming pipeline.

    `arm()` (re)starts a Timer that SIGKILLs the process if no further `arm()`
    call lands within `timeout` seconds. `fired` reports whether the Timer
    actually killed the process. `timeout=None` makes every method a no-op.
    """

    def __init__(self, process: "subprocess.Popen[str]", timeout: Optional[float]):
        self._process = process
        self._timeout = timeout
        self._timer: Optional[threading.Timer] = None
        self.fired = False

    def arm(self) -> None:
        if self._timeout is None:
            return
        if self._timer is not None:
            self._timer.cancel()
        self._timer = threading.Timer(self._timeout, self._on_timeout)
        self._timer.daemon = True
        self._timer.start()

    def cancel(self) -> None:
        if self._timer is not None:
            self._timer.cancel()
            self._timer = None

    def _on_timeout(self) -> None:
        self.fired = True
        try:
            self._process.kill()
        except ProcessLookupError:
            # Child already exited between readline() EOF and timer firing.
            pass


class CommandRunner:
    """Run an external command with optional bounded wall-clock / idle timeouts.

    Timeout semantics (only applied when timeout= is set):
      synchronous mode → wall-clock timeout via subprocess.run(timeout=...)
      streaming mode   → idle timeout via threading.Timer that is re-armed on
                         every line of output, so a command that is actively
                         producing progress (helm install --wait, etc.) is not
                         killed while it's making progress.

    Default is opt-in: timeout=None preserves the previous unbounded-wait
    behaviour. Callers that are reachable from tests (see argo_service) set
    an explicit timeout= to bound hangs.
    """
    def __init__(self, command_root: str, command_args: Dict[str, Any], sensitive_fields: Optional[List[str]] = None,
                 run_as_detatched: bool = False, log_file: Optional[str] = None,
                 timeout: Optional[float] = None):
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
        # None disables the per-call timeout; any positive number is the budget.
        self.timeout = timeout

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
        run_kwargs: Dict[str, Any] = {
            "stdout": subprocess.PIPE,
            "stderr": subprocess.PIPE,
            "text": True,
            "check": True,
        }
        if self.timeout is not None:
            run_kwargs["timeout"] = self.timeout
        try:
            cmd_output = subprocess.run(self.command, **run_kwargs)
            self.print_output_if_enabled(holder=cmd_output, should_print=print_to_console, is_error=False)
            return CommandResult(success=True, value="Command executed successfully", output=cmd_output)
        except subprocess.TimeoutExpired as e:
            # subprocess.run already sent SIGKILL and reaped the child before raising.
            # stdout / stderr may be None if the child produced no output before
            # being killed; log what we have and preserve the command for triage.
            captured_stdout = e.stdout.decode() if isinstance(e.stdout, bytes) else e.stdout
            captured_stderr = e.stderr.decode() if isinstance(e.stderr, bytes) else e.stderr
            logger.error(
                f"Command timed out after {self.timeout}s: {' '.join(self.sanitized_command())}\n"
                f"  captured stdout: {captured_stdout if captured_stdout else '(none)'}\n"
                f"  captured stderr: {captured_stderr if captured_stderr else '(none)'}"
            )
            raise CommandRunnerError(-1, self.sanitized_command(), captured_stdout, captured_stderr) from e
        except subprocess.CalledProcessError as e:
            self.print_output_if_enabled(holder=e, should_print=print_on_error, is_error=True)
            raise CommandRunnerError(e.returncode, self.sanitized_command(), e.stdout, e.stderr)

    def _run_as_streaming_process(self, print_to_console: bool = True) -> CommandResult:
        start_time = time.monotonic()
        idle_watchdog: Optional[_IdleWatchdog] = None
        try:
            process = subprocess.Popen(
                self.command,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                universal_newlines=True,
            )
            # Idle-timeout: SIGKILL the child after self.timeout seconds of no
            # output. Re-armed on every line so a command making visible progress
            # (helm install --wait, buildx build, etc.) is never killed.
            idle_watchdog = _IdleWatchdog(process, self.timeout)
            idle_watchdog.arm()

            self._stream_output_to_console(process, idle_watchdog, print_to_console)
            return_code = process.wait()
            return self._build_streaming_result(return_code, idle_watchdog, start_time)
        except CommandRunnerError:
            raise
        except Exception as e:
            logger.error(f"Streaming command failed: {e}")
            raise CommandRunnerError(-1, self.sanitized_command(), None, str(e))
        finally:
            if idle_watchdog is not None:
                idle_watchdog.cancel()

    def _stream_output_to_console(self, process: "subprocess.Popen[str]",
                                  watchdog: "_IdleWatchdog", print_to_console: bool) -> None:
        if not process.stdout:
            return
        for line in iter(process.stdout.readline, ''):
            if not line:
                continue
            watchdog.arm()  # progress observed — reset the idle clock
            if print_to_console:
                sys.stdout.write(line)
                sys.stdout.flush()
            logger.debug(f"STDOUT: {line.rstrip()}")

    def _build_streaming_result(self, return_code: int, watchdog: "_IdleWatchdog",
                                start_time: float) -> CommandResult:
        if watchdog.fired:
            elapsed = time.monotonic() - start_time
            logger.error(
                f"Command idle-timed-out after {self.timeout}s of no output "
                f"(ran for {elapsed:.1f}s): {' '.join(self.sanitized_command())}"
            )
            raise CommandRunnerError(-1, self.sanitized_command(), None,
                                     f"idle-timed-out after {self.timeout}s of silence")
        if return_code == 0:
            return CommandResult(success=True, value="Command executed successfully")
        raise CommandRunnerError(return_code, self.sanitized_command())

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
