import json
import os

from upgrade_testing_framework.cluster_management.docker_framework_client import DockerFrameworkClient
from upgrade_testing_framework.core.workspace_wrangler import WorkspaceWrangler

class FrameworkState:
    def __init__(self, state: dict, docker_client: DockerFrameworkClient = None):
        self.docker_client = docker_client
        self._state = state

    def __str__(self) -> str:
        return str(self._state)

    def get(self, key: str) -> any:
        return self._state.get(key, None)

    def set(self, key: str, value: any) -> any:
        self._state[key] = value
        return value

def get_initial_state(test_config_path: str) -> FrameworkState:
    beginning_state = {}
    beginning_state['test_config_path'] = test_config_path

    return FrameworkState(beginning_state)