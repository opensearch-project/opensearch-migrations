#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

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
        # Compute index names that are present in both the source and target
        indices_intersection = set(source.keys()) & set(target.keys())
        # Check if these "common" indices are identical or have metadata conflicts
        for index in indices_intersection:
            # Check settings
            if utils.has_differences(SETTINGS_KEY, source[index], target[index]):
                self.conflicting_indices.add(index)
            # Check mappings
            if utils.has_differences(MAPPINGS_KEY, source[index], target[index]):
                self.conflicting_indices.add(index)
        # Identical indices are the subset that do not have metadata conflicts
        self.identical_indices = set(indices_intersection) - set(self.conflicting_indices)
        # Indices that are not already on the target need to be created
        self.indices_to_create = set(source.keys()) - set(indices_intersection)

    def set_identical_empty_indices(self, indices: set):
        self.identical_empty_indices = indices
