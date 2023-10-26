from dataclasses import dataclass


@dataclass
class MigrationMonitorCounters:
    prev_no_partitions_count: int = 0
    prev_success_docs: int = -1
    idle_success_doc_count: int = 0
    seq_metric_api_fail: int = 0
    seq_metric_value_fail: int = 0
