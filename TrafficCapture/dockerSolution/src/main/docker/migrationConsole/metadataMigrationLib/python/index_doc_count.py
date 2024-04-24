#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

from dataclasses import dataclass


# Captures the doc_count for indices in a cluster, and also computes a total
@dataclass
class IndexDocCount:
    total: int
    index_doc_count_map: dict
