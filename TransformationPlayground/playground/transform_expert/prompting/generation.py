from typing import Any, Callable, Dict

from langchain_core.messages import SystemMessage

from transform_expert.parameters import SourceVersion, TargetVersion, TransformType, TransformLanguage
from transform_expert.prompting.knowledge import get_source_guidance, get_source_knowledge, get_target_guidance, get_target_knowledge
from transform_expert.prompting.templates import python_index_prompt_template


def _get_base_template(source_version: SourceVersion, target_version: TargetVersion, input_shape_type: TransformType, transform_language: TransformLanguage) -> str:
    if transform_language == TransformLanguage.PYTHON:
        if input_shape_type == TransformType.INDEX:
            return python_index_prompt_template
    
    raise NotImplementedError(f"Transform combination {source_version}, {target_version}, {input_shape_type}, {transform_language} not supported.")

def get_system_prompt_factory(source_version: SourceVersion, target_version: TargetVersion, input_shape_type: TransformType, transform_language: TransformLanguage) -> Callable[[Dict[str, Any]], SystemMessage]:
    base_template = _get_base_template(source_version, target_version, input_shape_type, transform_language)
    
    def factory(user_guidance: str, input_shape: Dict[str, Any]) -> SystemMessage:
        return SystemMessage(
            content=base_template.format(
                source_version=source_version,
                source_guidance=get_source_guidance(source_version, target_version, input_shape_type, transform_language),
                source_knowledge=get_source_knowledge(source_version, target_version, input_shape_type, transform_language),
                target_version=target_version,
                target_guidance=get_target_guidance(source_version, target_version, input_shape_type, transform_language),
                target_knowledge=get_target_knowledge(source_version, target_version, input_shape_type, transform_language),
                source_json=input_shape,
                user_guidance=user_guidance
            )
        )
    
    return factory