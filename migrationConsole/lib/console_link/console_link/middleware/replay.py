import logging
from typing import Dict, Tuple

from console_link.models.command_result import CommandResult
from console_link.middleware.error_handler import handle_errors
from console_link.middleware.json_support import support_json_return
from console_link.models.replayer_base import Replayer
from console_link.models.utils import ExitCode
from functools import partial

logger = logging.getLogger(__name__)


@support_json_return()
def describe(replayer: Replayer, as_json=False) -> Tuple[ExitCode, Dict]:
    return (ExitCode.SUCCESS, replayer.describe())


handle_replay_errors = partial(handle_errors, service_type="replayer")


@handle_replay_errors(on_success=lambda result: (ExitCode.SUCCESS,
                                                 "Replayer started successfully." + "\n" + result))
def start(replayer: Replayer, *args, **kwargs) -> CommandResult[str]:
    logger.info("Starting replayer")
    return replayer.start(*args, **kwargs)


@handle_replay_errors(on_success=lambda result: (ExitCode.SUCCESS,
                                                 "Replayer stopped successfully." + "\n" + result))
def stop(replayer: Replayer, *args, **kwargs) -> CommandResult[str]:
    logger.info("Stopping replayer")
    return replayer.stop(*args, **kwargs)


@handle_replay_errors(on_success=lambda result: (ExitCode.SUCCESS,
                                                 "Replayer scaled successfully." + "\n" + result))
def scale(replayer: Replayer, units: int, *args, **kwargs) -> CommandResult[str]:
    logger.info(f"Scaling replayer to {units} units")
    return replayer.scale(units, *args, **kwargs)


@handle_replay_errors(
    on_success=lambda status: (ExitCode.SUCCESS, f"{status[0]}\n{status[1]}"))
def status(replayer: Replayer, *args, **kwargs) -> CommandResult[str]:
    logger.info("Getting replayer status")
    return replayer.get_status(*args, **kwargs)
