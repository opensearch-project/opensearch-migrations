from dataclasses import dataclass
import logging
from typing import List

from langchain_core.tools import StructuredTool
from pydantic import BaseModel, Field

from transform_expert.utils.transforms import TransformPython
from transform_expert.parameters import TransformLanguage


logger = logging.getLogger("transform_expert")


"""
This module contains the LLM tools for each transformation modality, and functionality to pull the correct set of tools
for a given transformation.
"""

@dataclass
class ToolBundle:
    make_transform_tool: StructuredTool

    def to_list(self) -> List[StructuredTool]:
        return [self.make_transform_tool]

def get_tool_bundle(transform_language: TransformLanguage) -> ToolBundle:
    if transform_language == TransformLanguage.PYTHON:
        return ToolBundle(
            make_transform_tool=make_python_transform_tool
        )
    raise NotImplementedError(f"No tool bundle found for transform language: {transform_language}")

class MakePythonTransform(BaseModel):
    """Makes a Python transformation function to convert the source shape to the target shape."""
    imports: str = Field(description="An executable code that imports all necessary modules for the transform.")
    description: str = Field(description="A description of the transformation logic.")
    code: str = Field(description="The executable transformation code that converts the source shape to the target shape.")

def make_python_transform(imports: str, description: str, code: str) -> TransformPython:
    return TransformPython(imports=imports, description=description, code=code)

make_python_transform_tool = StructuredTool.from_function(
    func=make_python_transform,
    name="MakePythonTransform",
    args_schema=MakePythonTransform
)
