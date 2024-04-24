#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

class RequestError(RuntimeError):
    def __init__(self, message=None):
        super().__init__(message)


class IndexManagementError(RuntimeError):
    def __init__(self, message=None):
        super().__init__(message)


class MetadataMigrationError(RuntimeError):
    def __init__(self, message=None):
        super().__init__(message)
