import utils
from index_operations import SETTINGS_KEY, MAPPINGS_KEY


# Computes and captures differences in indices between a "source" cluster
# and a "target" cluster. Indices that exist on the source cluster but not
# on the target cluster are considered "to-create". "Conflicting" indices
# are present on both source and target clusters, but differ in their index
# settings or mappings.
class IndexDiff:
    indices_to_create: set
    identical_indices: set
    identical_empty_indices: set
    conflicting_indices: set

    def __init__(self, source: dict, target: dict):
        self.identical_empty_indices = set()
        self.conflicting_indices = set()
        indices_in_target = set(source.keys()) & set(target.keys())
        for index in indices_in_target:
            # Check settings
            if utils.has_differences(SETTINGS_KEY, source[index], target[index]):
                self.conflicting_indices.add(index)
            # Check mappings
            if utils.has_differences(MAPPINGS_KEY, source[index], target[index]):
                self.conflicting_indices.add(index)
        self.identical_indices = set(indices_in_target) - set(self.conflicting_indices)
        self.indices_to_create = set(source.keys()) - set(indices_in_target)

    def set_identical_empty_indices(self, indices: set):
        self.identical_empty_indices = indices
