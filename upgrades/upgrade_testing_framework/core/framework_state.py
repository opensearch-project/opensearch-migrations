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

def get_initial_state(workspace: WorkspaceWrangler, first_step: str, is_resume: bool) -> FrameworkState:
    if is_resume and os.path.isfile(workspace.state_file):
        with open(workspace.state_file, 'r') as state_file_handle:
            beginning_state = json.load(state_file_handle)
    else:
        beginning_state = {}

    if not beginning_state.get('current_step', None):
        beginning_state['current_step'] = first_step

    return FrameworkState(beginning_state)