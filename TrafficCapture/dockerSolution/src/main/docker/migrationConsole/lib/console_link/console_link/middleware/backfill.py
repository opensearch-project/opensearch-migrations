import json
import logging
from typing import Tuple
import yaml

from console_link.middleware.exit_code import ExitCode
from console_link.environment import Environment


logger = logging.getLogger(__name__)


def describe(env: Environment, as_json=False) -> str:
    backfill = env.backfill
    response = backfill.describe()
    if as_json:
        return json.dumps(response)
    return yaml.safe_dump(response)


def create(env: Environment, *args, **kwargs) -> Tuple[ExitCode, str]:
    backfill = env.backfill
    logger.info(f"Creating backfill with {args=} and {kwargs=}")
    try:
        result = backfill.create(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Create is not implemented for backfill {type(backfill).__name__}")
        return ExitCode.FAILURE, f"Create is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to create backfill: {e}")
        return ExitCode.FAILURE, f"Failure when creating backfill: {type(e).__name__} {e}"
    
    if result.success:
        return ExitCode.SUCCESS, "Backfill created successfully." + "\n" + result.display()
    return ExitCode.FAILURE, "Backfill creation failed." + "\n" + result.display()


def start(env: Environment, *args, **kwargs) -> Tuple[ExitCode, str]:
    backfill = env.backfill
    try:
        result = backfill.start(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Start is not implemented for backfill {type(backfill).__name__}")
        return ExitCode.FAILURE, f"Start is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to start backfill: {e}")
        return ExitCode.FAILURE, f"Failure when starting backfill: {type(e).__name__} {e}"
    
    if result.success:
        return ExitCode.SUCCESS, "Backfill started successfully." + "\n" + result.display()
    return ExitCode.FAILURE, "Backfill start failed." + "\n" + result.display()


def stop(env: Environment, *args, **kwargs) -> Tuple[ExitCode, str]:
    backfill = env.backfill
    logger.info("Stopping backfill")
    try:
        result = backfill.stop(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Stop is not implemented for backfill {type(backfill).__name__}")
        return ExitCode.FAILURE, f"Stop is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to stop backfill: {e}")
        return ExitCode.FAILURE, f"Failure when stopping backfill: {type(e).__name__} {e}"
    if result.success:
        return ExitCode.SUCCESS, "Backfill stopped successfully." + "\n" + result.display()
    return ExitCode.FAILURE, "Backfill stop failed." + "\n" + result.display()


def scale(env: Environment, units: int, *args, **kwargs) -> Tuple[ExitCode, str]:
    backfill = env.backfill
    logger.info(f"Scaling backfill to {units} units")
    try:
        result = backfill.scale(units, *args, **kwargs)
    except NotImplementedError:
        logger.error(f"Scale is not implemented for backfill {type(backfill).__name__}")
        return ExitCode.FAILURE, f"Scale is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to scale backfill: {e}")
        return ExitCode.FAILURE, f"Failure when scaling backfill: {type(e).__name__} {e}"
    if result.success:
        return ExitCode.SUCCESS, "Backfill scaled successfully." + "\n" + result.display()
    return ExitCode.FAILURE, "Backfill scale failed." + "\n" + result.display()


def status(env: Environment, *args, **kwargs) -> Tuple[ExitCode, str]:
    backfill = env.backfill
    logger.info("Getting backfill status")
    try:
        status = backfill.get_status(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Status is not implemented for backfill {type(backfill).__name__}")
        return ExitCode.FAILURE, f"Status is not implemented for backfill: {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to get status of backfill: {e}")
        return ExitCode.FAILURE, f"Failure when getting status of backfill: {type(e).__name__} {e}"
    if status:
        return ExitCode.SUCCESS, status.value
    return ExitCode.FAILURE, "Backfill status retrieval failed." + "\n" + status
