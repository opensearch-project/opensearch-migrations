import json
from typing import List

from upgrade_testing_framework.cluster_management.docker_framework_client import DockerFrameworkClient, DockerVolume
from upgrade_testing_framework.cluster_management.cluster import Cluster
from upgrade_testing_framework.core.test_config_wrangling import TestConfig

"""
The long-term trajectory of this class is a bit unclear.  We currently store both key/value pairs in a dictionary as
well as specifically declared/assigned objects.  That works for us currently, but we should be open to moving entirely
to one or the other approach if we see the need/opportunity.  KV-pairs are better for quick assignment if we have a
bunch of things to assign/re-use, but declared objects are better for code clarity and make using an IDE easier.  We'll
likely learn more about what this *should* be once we start running real tests through the UTF and support multiple
upgrade styles, as that should force changes in this class.

In general though, it is recommended that this be an append-only data store for the application.
"""
class FrameworkState:
    def __init__(self, state: dict):
        self.docker_client: DockerFrameworkClient = None
        self.source_cluster: Cluster = None
        self.target_cluster: Cluster = None
        self.test_config: TestConfig = None
        self.eligible_expectations = []
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
            "test_config": self.test_config.to_dict() if self.test_config else None,
            "eligible_expectations": self.eligible_expectations
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