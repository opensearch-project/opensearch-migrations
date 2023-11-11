from dataclasses import dataclass


# Captures the doc_count for indices in a cluster, and also computes a total
@dataclass
class IndexDocCount:
    total: int
    index_doc_count_map: dict
