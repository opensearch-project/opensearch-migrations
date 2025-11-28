import logging
from typing import Dict, Tuple
from functools import partial

from console_link.middleware.error_handler import handle_errors
from console_link.middleware.json_support import support_json_return
from console_link.models.backfill_base import Backfill, BackfillStatus
from console_link.models.command_result import CommandResult
from console_link.models.utils import ExitCode


logger = logging.getLogger(__name__)


@support_json_return()
def describe(backfill: Backfill, as_json=False) -> Tuple[ExitCode, Dict]:
    response = backfill.describe()
    return (ExitCode.SUCCESS, response)


handle_backfill_errors = partial(handle_errors, service_type="backfill")


@handle_errors("backfill",
               on_success=lambda result: (ExitCode.SUCCESS, "Backfill started successfully." + "\n" + result))
def start(backfill: Backfill, *args, **kwargs) -> CommandResult[str]:
    logger.info("Starting backfill")
    return backfill.start(*args, **kwargs)


@handle_errors("backfill",
               on_success=lambda result: (ExitCode.SUCCESS, "Backfill paused successfully." + "\n" + result))
def pause(backfill: Backfill, *args, **kwargs) -> CommandResult[str]:
    logger.info("Pausing backfill")
    return backfill.pause(*args, **kwargs)


@handle_errors("backfill",
               on_success=lambda result: (ExitCode.SUCCESS, "Backfill stopped successfully." + "\n" + result))
def stop(backfill: Backfill, *args, **kwargs) -> CommandResult[str]:
    logger.info("Stopping backfill")
    return backfill.stop(*args, **kwargs)


@handle_errors("backfill",
               on_success=lambda status: (ExitCode.SUCCESS, f"{status[0]}\n{status[1]}"))
def status(backfill: Backfill, deep_check: bool, *args, **kwargs) -> CommandResult[Tuple[BackfillStatus, str]]:
    logger.info(f"Getting backfill status with {deep_check=}")
    return backfill.get_status(deep_check, *args, **kwargs)


@handle_errors("backfill",
               on_success=lambda status: (ExitCode.SUCCESS, status))
def scale(backfill: Backfill, units: int, *args, **kwargs) -> CommandResult[str]:
    logger.info(f"Scaling backfill to {units} units")
    return backfill.scale(units, *args, **kwargs)


@handle_errors("backfill")
def archive(backfill: Backfill, *args, **kwargs) -> CommandResult[str]:
    logger.info("Archiving backfill operation")
    return backfill.archive(*args, **kwargs)
