#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#


# Constants
from typing import Optional

NAME_KEY = "name"
DEFAULT_TEMPLATE_KEY = "component_template"


# Class that encapsulates component template information
class ComponentTemplateInfo:
    # Private member variables
    __name: str
    __template_def: Optional[dict]

    def __init__(self, template_payload: dict, template_key: str = DEFAULT_TEMPLATE_KEY):
        self.__name = template_payload[NAME_KEY]
        self.__template_def = None
        if template_key in template_payload:
            self.__template_def = template_payload[template_key]

    def get_name(self) -> str:
        return self.__name

    def get_template_definition(self) -> dict:
        return self.__template_def
