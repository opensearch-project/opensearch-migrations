from typing import Dict, List

import upgrade_testing_framework.core.versions_engine as ev

def _get_engine_data_dir_for_version(version: ev.EngineVersion) -> str:
    if ev.ENGINE_ELASTICSEARCH == version.engine:
        return "/usr/share/elasticsearch/data"
    return "/usr/share/opensearch/data"

def _get_engine_user_for_version(version: ev.EngineVersion) -> str:
    if ev.ENGINE_ELASTICSEARCH == version.engine:
        return "elasticsearch"
    return "elasticsearch"

class NodeConfiguration:
    def __init__(self, engine_version: ev.EngineVersion, node_name: str, cluster_name: str, master_nodes: List[str], 
            seed_hosts: List[str], additional_config: Dict[str, str] = {}):

        self.engine_version = engine_version

        self.data_dir: str = _get_engine_data_dir_for_version(engine_version)
        self.user: str = _get_engine_user_for_version(engine_version)

        self.config = {
            # Core configuration
            "cluster.name": cluster_name,
            "cluster.initial_master_nodes": ",".join(master_nodes),
            "discovery.seed_hosts": ",".join(seed_hosts),
            "node.name": node_name,

            # Stuff we might change later
            "bootstrap.memory_lock": "true"
        }
        self.config.update(additional_config)

    def to_dict(self) -> dict:
        return {
            "config": self.config,
            "data_dir": self.data_dir,
            "engine_version": str(self.engine_version),
            "user": self.user
        }

