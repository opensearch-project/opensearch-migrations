from dataclasses import dataclass

@dataclass
class ClusterSnapshot:
    repo_name: str
    snapshot_id: str

    def to_dict(self) -> dict:
        return {
            "repo_name": self.repo_name,
            "snapshot_id": self.snapshot_id
        }
