from dataclasses import dataclass
import logging
from typing import Dict


from langchain_core.tools import StructuredTool
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)

@dataclass
class Transform:
    imports: str
    description: str
    code: str

    def to_json(self) -> Dict[str, str]:
        return {
            "imports": self.imports,
            "description": self.description,
            "code": self.code
        }

class MakeTransform(BaseModel):
    """Makes a Python transformation function to convert the source JSON to the target JSON."""
    imports: str = Field(description="An executable code that imports all necessary modules for the transform.")
    description: str = Field(description="A description of the transformation logic.")
    code: str = Field(description="The executable transformation code that converts the source JSON to the target JSON.")

def make_transform(imports: str, description: str, code: str) -> Transform:
    return Transform(imports=imports, description=description, code=code)

make_transform_tool = StructuredTool.from_function(
    func=make_transform,
    name="MakeTransform",
    args_schema=MakeTransform
)

TOOLS_NORMAL = [make_transform_tool]
TOOLS_ALL = TOOLS_NORMAL
