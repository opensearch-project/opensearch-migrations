import json

from upgrade_testing_framework.cluster_management.docker_framework_client import DockerFrameworkClient
from upgrade_testing_framework.cluster_management.cluster import Cluster
from upgrade_testing_framework.core.test_config_wrangling import TestClustersDef

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
        self.test_config: TestClustersDef = None
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