import logging
from typing import Any, Callable, Dict, Tuple

from console_link.middleware.json_support import support_json_return
from console_link.models.backfill_base import Backfill, BackfillStatus
from console_link.models.command_result import CommandResult
from console_link.models.utils import ExitCode


logger = logging.getLogger(__name__)


def handle_errors(on_success: Callable[[Any], Tuple[ExitCode, str]]) -> Callable[[Any], Tuple[ExitCode, str]]:
    def decorator(func: Callable[[Any], Tuple[ExitCode, str]]) -> Callable[[Any], Tuple[ExitCode, str]]:
        def wrapper(backfill: Backfill, *args, **kwargs) -> Tuple[ExitCode, str]:
            try:
                result = func(backfill, *args, **kwargs)
            except NotImplementedError:
                logger.error(f"{func.__name__} is not implemented for backfill {type(backfill).__name__}")
                return ExitCode.FAILURE, f"{func.__name__} is not implemented for backfill {type(backfill).__name__}"
            except Exception as e:
                logger.error(f"Failed to {func.__name__} backfill: {e}")
                return ExitCode.FAILURE, f"Failure on {func.__name__} for backfill: {type(e).__name__} {e}"
            return on_success(result.value)
        return wrapper
    return decorator


@support_json_return()
def describe(backfill: Backfill, as_json=False) -> Tuple[ExitCode, Dict]:
    response = backfill.describe()
    return (ExitCode.SUCCESS, response)


@handle_errors(on_success=lambda result: (ExitCode.SUCCESS, "Backfill created successfully." + "\n" + result.display()))
def create(backfill: Backfill, *args, **kwargs) -> CommandResult[str]:
    logger.info(f"Creating backfill with {args=} and {kwargs=}")
    return backfill.create(*args, **kwargs)


@handle_errors(on_success=lambda result: (ExitCode.SUCCESS, "Backfill started successfully." + "\n" + result.display()))
def start(backfill: Backfill, *args, **kwargs) -> CommandResult[str]:
    logger.info("Starting backfill")
    return backfill.start(*args, **kwargs)


@handle_errors(on_success=lambda result: (ExitCode.SUCCESS, "Backfill stopped successfully." + "\n" + result.display()))
def stop(backfill: Backfill, *args, **kwargs) -> CommandResult[str]:
    logger.info("Stopping backfill")
    return backfill.stop(*args, **kwargs)


@handle_errors(on_success=lambda status: (ExitCode.SUCCESS, f"{status[0]}\n{status[1]}"))
def status(backfill: Backfill, deep_check: bool, *args, **kwargs) -> CommandResult[Tuple[BackfillStatus, str]]:
    logger.info(f"Getting backfill status with {deep_check=}")
    return backfill.get_status(deep_check, *args, **kwargs)


@handle_errors(on_success=lambda status: (ExitCode.SUCCESS, f"{status[0]}\n{status[1]}"))
def scale(backfill: Backfill, units: int, *args, **kwargs) -> CommandResult[str]:
    logger.info(f"Scaling backfill to {units} units")
    return backfill.scale(units, *args, **kwargs)
