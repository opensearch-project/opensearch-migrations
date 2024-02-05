#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#
import unittest

from component_template_info import ComponentTemplateInfo
from index_template_info import IndexTemplateInfo


class TestTemplateInfo(unittest.TestCase):
    # Template info expects at least a NAME key
    def test_bad_template_info(self):
        with self.assertRaises(KeyError):
            ComponentTemplateInfo({})
            IndexTemplateInfo({})

    def test_empty_template_info(self):
        template_payload: dict = {"name": "test", "template": "dontcare"}
        info = ComponentTemplateInfo(template_payload)
        self.assertEqual("test", info.get_name())
        self.assertIsNone(info.get_template_definition())

    def test_component_template_info(self):
        test_template = {"test": 1}
        template_payload: dict = {"name": "test", "component_template": test_template}
        info = ComponentTemplateInfo(template_payload)
        self.assertEqual("test", info.get_name())
        self.assertEqual(test_template, info.get_template_definition())
        info = IndexTemplateInfo(template_payload)
        self.assertEqual("test", info.get_name())
        # Index template uses a different key, so this should be None
        self.assertIsNone(info.get_template_definition())

    def test_index_template_info(self):
        test_template = {"test": 1}
        template_payload: dict = {"name": "test", "index_template": test_template}
        info = IndexTemplateInfo(template_payload)
        self.assertEqual("test", info.get_name())
        self.assertEqual(test_template, info.get_template_definition())
        info = ComponentTemplateInfo(template_payload)
        self.assertEqual("test", info.get_name())
        # Component template uses a different key, so this should be None
        self.assertIsNone(info.get_template_definition())


if __name__ == '__main__':
    unittest.main()
