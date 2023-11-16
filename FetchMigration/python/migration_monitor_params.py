#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

from dataclasses import dataclass


@dataclass
class MigrationMonitorParams:
    target_count: int
    data_prepper_endpoint: str
    is_local_process: bool = False
