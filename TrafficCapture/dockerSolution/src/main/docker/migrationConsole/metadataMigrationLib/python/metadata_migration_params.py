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
class MetadataMigrationParams:
    config_file_path: str
    output_file: str = ""
    report: bool = False
    dryrun: bool = False
