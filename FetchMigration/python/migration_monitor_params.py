from dataclasses import dataclass


@dataclass
class MigrationMonitorParams:
    target_count: int
    data_prepper_endpoint: str
