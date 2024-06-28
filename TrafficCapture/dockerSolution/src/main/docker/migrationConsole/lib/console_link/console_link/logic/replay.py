import json
import logging
from typing import Tuple
from console_link.models.utils import ExitCode
from console_link.models.replayer_base import Replayer
import yaml


logger = logging.getLogger(__name__)


def describe(replayer: Replayer, as_json=False) -> str:
    response = replayer.describe()
    if as_json:
        return json.dumps(response)
    return yaml.safe_dump(response)


def start(replayer: Replayer, *args, **kwargs) -> Tuple[ExitCode, str]:
    try:
        result = replayer.start(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Start is not implemented for replayer {type(replayer).__name__}")
        return ExitCode.FAILURE, f"Start is not implemented for replayer {type(replayer).__name__}"
    except Exception as e:
        logger.error(f"Failed to start replayer: {e}")
        return ExitCode.FAILURE, f"Failure when starting replayer: {type(e).__name__} {e}"
    
    if result.success:
        return ExitCode.SUCCESS, "Replayer started successfully." + "\n" + result.display()
    return ExitCode.FAILURE, "Replayer start failed." + "\n" + result.display()


def stop(replayer: Replayer, *args, **kwargs) -> Tuple[ExitCode, str]:
    logger.info("Stopping replayer")
    try:
        result = replayer.stop(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Stop is not implemented for replayer {type(replayer).__name__}")
        return ExitCode.FAILURE, f"Stop is not implemented for replayer {type(replayer).__name__}"
    except Exception as e:
        logger.error(f"Failed to stop replayer: {e}")
        return ExitCode.FAILURE, f"Failure when stopping replayer: {type(e).__name__} {e}"
    if result.success:
        return ExitCode.SUCCESS, "Replayer stopped successfully." + "\n" + result.display()
    return ExitCode.FAILURE, "Replayer stop failed." + "\n" + result.display()


def scale(replayer: Replayer, units: int, *args, **kwargs) -> Tuple[ExitCode, str]:
    logger.info(f"Scaling replayer to {units} units")
    try:
        result = replayer.scale(units, *args, **kwargs)
    except NotImplementedError:
        logger.error(f"Scale is not implemented for replayer {type(replayer).__name__}")
        return ExitCode.FAILURE, f"Scale is not implemented for replayer {type(replayer).__name__}"
    except Exception as e:
        logger.error(f"Failed to scale replayer: {e}")
        return ExitCode.FAILURE, f"Failure when scaling replayer: {type(e).__name__} {e}"
    if result.success:
        return ExitCode.SUCCESS, "Replayer scaled successfully." + "\n" + result.display()
    return ExitCode.FAILURE, "Replayer scale failed." + "\n" + result.display()


def status(replayer: Replayer, *args, **kwargs) -> Tuple[ExitCode, str]:
    logger.info("Getting replayer status")
    try:
        status = replayer.get_status(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Status is not implemented for replayer {type(replayer).__name__}")
        return ExitCode.FAILURE, f"Status is not implemented for replayer: {type(replayer).__name__}"
    except Exception as e:
        logger.error(f"Failed to get status of replayer: {e}")
        return ExitCode.FAILURE, f"Failure when getting status of replayer: {type(e).__name__} {e}"
    if status:
        return ExitCode.SUCCESS, status.value
    return ExitCode.FAILURE, "Replayer status retrieval failed." + "\n" + status
