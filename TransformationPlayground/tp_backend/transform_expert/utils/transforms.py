import asyncio
from dataclasses import dataclass
import importlib
import logging
import os
from typing import Any, Callable, Dict, List

from botocore.config import Config
from langchain_aws import ChatBedrockConverse
from langchain_core.language_models import LanguageModelInput
from langchain_core.messages import BaseMessage, ToolMessage
from langchain_core.runnables import Runnable
from langchain_core.tools import StructuredTool
from pydantic import BaseModel, Field

from transform_expert.utils.inference import InferenceTask, perform_async_inference



logger = logging.getLogger("transform_expert")


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
    
    def to_file_format(self) -> str:
        return f"{self.imports}\n\n\"\"\"\n{self.description}\n\"\"\"\n\n{self.code}"
    
@dataclass
class TransformTask:
    transform_id: str
    context: List[BaseMessage]
    llm: Runnable[LanguageModelInput, BaseMessage]
    transform: Transform

    def to_json(self) -> Dict[str, Any]:
        return {
            "transform_id": self.transform_id,
            "context": [turn.to_json() for turn in self.context],
            "transform": self.transform.to_json() if self.transform else None
        }
    
    def to_inference_task(self) -> InferenceTask:
        return InferenceTask(
            transform_id=self.transform_id,
            context=self.context,
            llm=self.llm
        )


class MakePythonTransform(BaseModel):
    """Makes a Python transformation function to convert the source JSON to the target JSON."""
    imports: str = Field(description="An executable code that imports all necessary modules for the transform.")
    description: str = Field(description="A description of the transformation logic.")
    code: str = Field(description="The executable transformation code that converts the source JSON to the target JSON.")

def make_python_transform(imports: str, description: str, code: str) -> Transform:
    return Transform(imports=imports, description=description, code=code)

make_python_transform_tool = StructuredTool.from_function(
    func=make_python_transform,
    name="MakePythonTransform",
    args_schema=MakePythonTransform
)

TOOLS_MAKE_TRANSFORM = [make_python_transform_tool]



# Define a boto Config to use w/ our LLMs that's more resilient to long waits and frequent throttling
config = Config(
    read_timeout=120, # Wait 2 minutes for a response from the LLM
    retries={
        'max_attempts': 20,  # Increase the number of retry attempts
        'mode': 'adaptive'   # Use adaptive retry strategy for better throttling handling
    }
)

# Define our LLMs
llm_python_transform = ChatBedrockConverse(
    model="anthropic.claude-3-5-sonnet-20240620-v1:0",
    temperature=0,
    max_tokens=4096,
    region_name="us-west-2",
    config=config
)
llm_python_w_tools = llm_python_transform.bind_tools(TOOLS_MAKE_TRANSFORM)

def invoke_transform_expert(task: TransformTask) -> TransformTask:
    logger.info(f"Invoking the Transform Expert for transform_id: {task.transform_id}")
    logger.debug(f"Transform Task: {str(task.to_json())}")

    # Invoke the LLM.  This should result in the LLM making a tool call, forcing it to create the transform details by
    # conforming to the tool's schema.
    inference_task = task.to_inference_task()
    inference_result = asyncio.run(perform_async_inference([inference_task]))[0]

    logger.debug(f"Inference Result: {str(inference_result.to_json())}")

    # Append the LLM response to the context
    task.context.append(inference_result.response)

    # Perform the tool call using the LLM's response to create our Transform object
    tool_call = inference_result.response.tool_calls[-1]
    transform = make_python_transform_tool(tool_call["args"])
    task.transform = transform

    # Append the tool call to the context
    task.context.append(
        ToolMessage(
                name="MakePythonTransform",
                content="Created the transform",
                tool_call_id=tool_call["id"]
        )
    )

    logger.info(f"Transform Expert completed for transform_id: {task.transform_id}")
    logger.debug(f"Updated Transform Task: {str(task.to_json())}")

    return task

def get_transform_file_path(transform_files_dir: str, transform_id: str) -> str:
    return os.path.join(transform_files_dir, f"{transform_id}_transform.py")

def get_transform_input_file_path(transform_files_dir: str, transform_id: str) -> str:
    return os.path.join(transform_files_dir, f"{transform_id}_input.json")

def get_transform_output_file_path(transform_files_dir: str, transform_id: str) -> str:
    return os.path.join(transform_files_dir, f"{transform_id}_output.json")

def get_transform_report_file_path(transform_files_dir: str, transform_id: str) -> str:
    return os.path.join(transform_files_dir, f"{transform_id}_report.json")

def load_transform_from_file(transform_file_path: str) -> Callable[[Dict[str, Any]], List[Dict[str, Any]]]:
    module_spec = importlib.util.spec_from_file_location("transform", transform_file_path)
    if module_spec is None:
        raise ImportError(f"Cannot load the transform module from {transform_file_path}")
    module = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(module)
    return module.transform