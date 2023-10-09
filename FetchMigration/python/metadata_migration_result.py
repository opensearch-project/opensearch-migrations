from dataclasses import dataclass, field


@dataclass
class MetadataMigrationResult:
    target_doc_count: int = 0
    created_indices: set = field(default_factory=set)
