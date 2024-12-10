from dataclasses import dataclass
import logging
from typing import Any, Callable, Dict

from botocore.config import Config
from langchain_aws import ChatBedrockConverse
from langchain_core.language_models import LanguageModelInput
from langchain_core.messages import BaseMessage, SystemMessage, ToolMessage
from langchain_core.runnables import Runnable


from transform_expert.utils.inference import perform_inference
from transform_expert.parameters import SourceVersion, TargetVersion, TransformType, TransformLanguage
from transform_expert.prompting import get_system_prompt_factory
from transform_expert.tools import ToolBundle, get_tool_bundle
from transform_expert.utils.transforms import TransformTask


logger = logging.getLogger("transform_expert")


@dataclass
class Expert:
    llm: Runnable[LanguageModelInput, BaseMessage]
    system_prompt_factory: Callable[[Dict[str, Any]], SystemMessage]
    tools: ToolBundle

def get_expert(source_version: SourceVersion, target_version: TargetVersion, transform_type: TransformType, transform_language: TransformLanguage) -> Expert:
    logger.info(f"Building expert for: {source_version}, {target_version}, {transform_type}, {transform_language}")

    # Get the tool bundle for the given transform language
    tool_bundle = get_tool_bundle(transform_language)

    # Define a boto Config to use w/ our LLM that's more resilient to long waits and frequent throttling
    config = Config(
        read_timeout=120, # Wait 2 minutes for a response from the LLM
        retries={
            'max_attempts': 20,  # Increase the number of retry attempts
            'mode': 'adaptive'   # Use adaptive retry strategy for better throttling handling
        }
    )

    # Define our Bedrock LLM and attach the tools to it
    llm = ChatBedrockConverse(
        model="anthropic.claude-3-5-sonnet-20240620-v1:0", # This is the older version of the model, could be updated
        temperature=0, # Suitable for straightforward, practical code generation
        max_tokens=4096,
        region_name="us-west-2", # Somewhat necessary to hardcode, as models are only available in limited regions
        config=config
    )
    llm_w_tools = llm.bind_tools(tool_bundle.to_list())

    return Expert(
        llm=llm_w_tools,
        system_prompt_factory=get_system_prompt_factory(
            source_version=source_version,
            target_version=target_version,
            input_shape_type=transform_type,
            transform_language=transform_language
        ),
        tools=tool_bundle
    )

def invoke_expert(expert: Expert, task: TransformTask) -> TransformTask:
    logger.info(f"Invoking the Transform Expert for transform_id: {task.transform_id}")
    logger.debug(f"Transform Task: {str(task.to_json())}")

    # Invoke the LLM.  This should result in the LLM making a tool call, forcing it to create the transform details by
    # conforming to the tool's schema.
    inference_task = task.to_inference_task()
    inference_result = perform_inference(expert.llm, [inference_task])[0]

    logger.debug(f"Inference Result: {str(inference_result.to_json())}")

    # Append the LLM response to the context
    task.context.append(inference_result.response)

    # Perform the tool call using the LLM's response to create our Transform object
    tool_call = inference_result.response.tool_calls[-1]
    transform = expert.tools.make_transform_tool(tool_call["args"])
    task.transform = transform

    # Append the tool call to the context
    task.context.append(
        ToolMessage(
                name="MakePythonTransform",
                content="Created the transform",
                tool_call_id=tool_call["id"]
        )
    )

    logger.info(f"Transform completed for transform_id: {task.transform_id}")
    logger.debug(f"Updated Transform Task: {str(task.to_json())}")

    return task