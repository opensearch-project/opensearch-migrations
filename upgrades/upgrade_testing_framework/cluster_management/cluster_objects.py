from dataclasses import dataclass


@dataclass
class ClusterSnapshot:
    repo_name: str
    snapshot_id: str
