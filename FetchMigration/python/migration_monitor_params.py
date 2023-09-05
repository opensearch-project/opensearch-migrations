from dataclasses import dataclass


@dataclass
class MigrationMonitorParams:
    target_count: int
    dp_endpoint: str = "https://localhost:4900"
