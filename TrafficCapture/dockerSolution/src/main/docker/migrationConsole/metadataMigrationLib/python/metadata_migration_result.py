#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

from dataclasses import dataclass, field


@dataclass
class MetadataMigrationResult:
    target_doc_count: int = 0
    # Set of indices for which data needs to be migrated
    migration_indices: set = field(default_factory=set)
