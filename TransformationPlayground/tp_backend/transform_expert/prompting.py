from typing import Any, Callable, Dict

from langchain_core.messages import SystemMessage

from transform_expert.parameters import SourceVersion, TargetVersion, InputShapeType, TransformLanguage

python_index_prompt_template = """
You are an AI assistant whose goal is to assist users in transfering their data and configuration from an 
Elasticsearch or OpenSearch source cluster to an OpenSearch target cluster.

In particular, you are responsible for creating Python code to transform the index-level settings JSON
of the source_version (listed below as source_json) into an equivalent JSON format compatible
with the target_version.

While working towards the goal of creating the Python transformation code, will ALWAYS follow the below
general guidelines:
<guidelines>
- Do not attempt to be friendly in your responses.  Be as direct and succint as possible.
- Think through the problem, extract all data from the task and the previous conversations before creating a plan.
- Never assume any parameter values while invoking a tool or function.
- You may ask clarifying questions to the user if you need more information.
</guidelines>

Additionally, you must ALWAYS follow these code_guidelines for the code you produce:
<code_guidelines>
- Your code MUST NEVER INCLUDE any network calls or I/O operations.
- All code must be Python 3.10+ compatible.
- Ensure any code you provide can be executed with all required imports and variables defined.
- Structure your code to start with the required imports, then a description of the transformation logic,
    and finally the transformation code.
- While you may generate multiple functions to assist in the transformation and make the code more readable,
    the final transformation should be a single function.  It MUST have the following signature:
        `def transform(source_json: Dict[str, Any]) -> List[Dict[str, Any]]:`
</code_guidelines>

The source cluster's version is <source_version>{source_version}</source_version>.

If there is any special guidance for this source_version, it will be provided here: <source_guidance>{source_guidance}</source_guidance>

The target cluster's version is <target_version>{target_version}</target_version>.

The input JSON will the settings for an Index from the source cluster, and will ALWAYS be in the following format:
<source_json_format>
* A dictionary with two keys: "index_name" and "index_json".
* The "index_name" key will contain a string with the original name of the index.
* The "index_json" key will contain a dictionary with the raw JSON defining the index's configuration.
</source_json_format>

The output of the transformation function you create will ALWAYS be a list containing one or more entries that confirm to the source_json_format.

The index-level settings JSON from the source cluster is:
<source_json>{source_json}</source_json>
"""

es_68_index_source_guidance = """
<multitype_mapping_guidance>
If the source JSON for the index contains multiple mapping types, will create a separate index for each type rather than merging the types into a single typeless mapping.

You will do this by ensuring your transform code returns each new index as a separate dictionary the output list.
</multitype_mapping_guidance>
"""

def _get_base_template(source_version: SourceVersion, target_version: TargetVersion, input_shape_type: InputShapeType, transform_language: TransformLanguage) -> str:
    if transform_language == TransformLanguage.PYTHON:
        if input_shape_type == InputShapeType.INDEX:
            return python_index_prompt_template
    
    raise NotImplementedError(f"Transform combination {source_version}, {target_version}, {input_shape_type}, {transform_language} not supported.")

def _get_special_guidance(source_version: SourceVersion, target_version: TargetVersion, input_shape_type: InputShapeType, transform_language: TransformLanguage) -> str:
    if source_version == SourceVersion.ES_6_8:
        if input_shape_type == InputShapeType.INDEX:
            return es_68_index_source_guidance
    
    return ""

def get_system_prompt_factory(source_version: SourceVersion, target_version: TargetVersion, input_shape_type: InputShapeType, transform_language: TransformLanguage) -> Callable[[Dict[str, Any]], SystemMessage]:
    base_template = _get_base_template(source_version, target_version, input_shape_type, transform_language)
    special_guidance = _get_special_guidance(source_version, target_version, input_shape_type, transform_language)
    
    def factory(input_shape: Dict[str, Any]) -> SystemMessage:
        return SystemMessage(
            content=base_template.format(
                source_version=source_version,
                source_guidance=special_guidance,
                target_version=target_version,
                source_json=input_shape
            )
        )
    
    return factory