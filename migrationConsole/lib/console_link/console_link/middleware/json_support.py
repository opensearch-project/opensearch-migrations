import json
from typing import Any, Callable, Dict, List, Tuple

import yaml

from console_link.models.utils import ExitCode


def support_json_return() -> Callable[[Tuple[ExitCode, Dict | List | str]], Tuple[ExitCode, str]]:
    def decorator(func: Callable[[Tuple[ExitCode, Dict | List | str]], Tuple[ExitCode, str]]) \
            -> Callable[[Any], Tuple[ExitCode, str]]:
        def wrapper(*args, as_json=False, **kwargs) -> Tuple[ExitCode, str]:
            result = func(*args, **kwargs)
            if as_json:
                return (result[0], json.dumps(result[1]))
            return (result[0], yaml.safe_dump(result[1]))
        return wrapper
    return decorator
