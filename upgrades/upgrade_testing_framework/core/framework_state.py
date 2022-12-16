import json
from typing import List

from upgrade_testing_framework.cluster_management.docker_framework_client import DockerFrameworkClient, DockerVolume
from upgrade_testing_framework.cluster_management.cluster import Cluster
from upgrade_testing_framework.core.test_config_wrangling import TestConfig

"""
This class needs some work.  Right now, we putting a mish-mash of key/value pairs and objects into it using a mix of
formal methods and direct assignment to un-initialized internal variables.  We need to come up with a formal strategy
for how the rest of the code should be interacting with it.

Additionally, want to be able to dump all useful information to the state file for debugging purposes.  However, we
currently only dump the key/value pairs in the _state_dict.  As a result, we don't get any information about the
cluster we're storing as an internal member during the FrameworkSteps.  We need to invest in this area of the code.
"""
class FrameworkState:
    def __init__(self, state: dict):
        self.docker_client: DockerFrameworkClient = None
        self.source_cluster: Cluster = None
        self.target_cluster: Cluster = None
        self.test_config: TestConfig = None
        self._app_state = state

        # The fact that we need to store this in our FrameworkState means we probably need to be more sophisticated in
        # order to support multiple upgrade types.  Since we're only supporting Snapshot/Restore for now, we can solve
        # that problem later.
        self.shared_volume: DockerVolume = None

    def to_dict(self) -> dict:
        return {
            "app_state": self._app_state,
            "shared_volume": self.shared_volume.to_dict() if self.shared_volume else None,
            "source_cluster": self.source_cluster.to_dict() if self.source_cluster else None,
            "target_cluster": self.target_cluster.to_dict() if self.target_cluster else None,
            "test_config": self.test_config.to_dict() if self.test_config else None
        }

    def __str__(self) -> str:
        return json.dumps(self.to_dict(), sort_keys=True, indent=4)

    def get_key(self, key: str) -> any:
        return self._app_state.get(key, None)

    def set_key(self, key: str, value: any) -> any:
        self._app_state[key] = value
        return value

def get_initial_state(test_config_path: str) -> FrameworkState:
    beginning_state = {}
    beginning_state['test_config_path'] = test_config_path

    return FrameworkState(beginning_state)