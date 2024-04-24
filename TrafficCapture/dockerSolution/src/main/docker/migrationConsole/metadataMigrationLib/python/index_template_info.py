#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

from component_template_info import ComponentTemplateInfo

# Constants
INDEX_TEMPLATE_KEY = "index_template"


# Class that encapsulates index template information from a cluster.
# Subclass of ComponentTemplateInfo because the structure of an index
# template is identical to a component template, except that it uses
# a different template key. Also, index templates can be "composed" of
# one or more component templates.
class IndexTemplateInfo(ComponentTemplateInfo):
    def __init__(self, template_payload: dict):
        super().__init__(template_payload, INDEX_TEMPLATE_KEY)
