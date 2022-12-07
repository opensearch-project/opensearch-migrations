import json

from upgrade_testing_framework.cluster_management.docker_framework_client import DockerFrameworkClient
from upgrade_testing_framework.core.workspace_wrangler import WorkspaceWrangler

class FrameworkState:
    def __init__(self, state: dict):
        self.docker_client: DockerFrameworkClient | None = None
        self._state_dict = state

    def __str__(self) -> str:
        return json.dumps(self._state_dict, sort_keys=True, indent=4) 

    def get_key(self, key: str) -> any:
        return self._state_dict.get(key, None)

    def set_key(self, key: str, value: any) -> any:
        self._state_dict[key] = value
        return value

def get_initial_state(test_config_path: str) -> FrameworkState:
    beginning_state = {}
    beginning_state['test_config_path'] = test_config_path

    return FrameworkState(beginning_state)