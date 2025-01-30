from unittest.mock import MagicMock

from django.test import TestCase

from transform_expert.utils.transforms import (TransformPython, load_transform, TransformInvalidSyntaxError,
                                               TransformNotInModuleError, TransformNotExecutableError)


class LoadTransformCase(TestCase):
    def test_invalid_syntax_in_transform(self):
        transform = TransformPython(
            imports="from typing import Dict, Any, List",
            description="A transform with invalid syntax",
            code="def transform(source_json: Dict[str, Any]) -> List[Dict[str, Any]]\n    return input"
        )

        with self.assertRaises(TransformInvalidSyntaxError):
            load_transform(transform)

    def test_improperly_named_transform(self):
        transform = TransformPython(
            imports="from typing import Dict, Any, List",
            description="A transform with an unexpected name",
            code="def transform_with_different_name(source_json: Dict[str, Any]) -> List[Dict[str, Any]]:\n [source_json]"
        )

        with self.assertRaises(TransformNotInModuleError):
            load_transform(transform)

    def test_non_executable_transform(self):
        transform = TransformPython(
            imports="",
            description="A transform that is not executable",
            code="transform = 42"
        )

        with self.assertRaises(TransformNotExecutableError):
            load_transform(transform)
