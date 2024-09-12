from typing import Dict, Optional
import logging
from cerberus import Validator

logger = logging.getLogger(__name__)

SCHEMA = {
    "client_options": {
        "type": "dict",
        "schema": {
            "user_agent_extra": {"type": "string", "required": False},
        },
    }
}


class ClientOptions:
    """
    Options that can be configured for boto3 and request library clients.
    """

    user_agent_extra: Optional[str] = None

    def __init__(self, config: Dict) -> None:
        logger.info(f"Initializing client options with config: {config}")
        v = Validator(SCHEMA)
        if not v.validate({'client_options': config}):
            raise ValueError("Invalid config file for client options", v.errors)

        self.user_agent_extra = config.get("user_agent_extra", None)
