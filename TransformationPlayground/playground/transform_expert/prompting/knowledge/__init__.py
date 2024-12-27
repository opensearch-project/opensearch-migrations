from transform_expert.parameters import SourceVersion, TargetVersion, TransformType, TransformLanguage

from transform_expert.prompting.knowledge.es_6_8 import INDEX_GUIDANCE as es_6_8_index_guidance, INDEX_KNOWLEDGE as es_6_8_index_knowledge
from transform_expert.prompting.knowledge.es_7_10 import INDEX_GUIDANCE as es_7_10_index_guidance, INDEX_KNOWLEDGE as es_7_10_index_knowledge
from transform_expert.prompting.knowledge.os_2_17 import INDEX_GUIDANCE as os_2_17_index_guidance, INDEX_KNOWLEDGE as os_2_17_index_knowledge


def get_source_guidance(source_version: SourceVersion, target_version: TargetVersion, input_shape_type: TransformType, transform_language: TransformLanguage) -> str:
    if source_version == SourceVersion.ES_6_8:
        if input_shape_type == TransformType.INDEX:
            return es_6_8_index_guidance
    elif source_version == SourceVersion.ES_7_10:
        if input_shape_type == TransformType.INDEX:
            return es_7_10_index_guidance
    
    return ""

def get_source_knowledge(source_version: SourceVersion, target_version: TargetVersion, input_shape_type: TransformType, transform_language: TransformLanguage) -> str:
    if source_version == SourceVersion.ES_6_8:
        if input_shape_type == TransformType.INDEX:
            return es_6_8_index_knowledge
    elif source_version == SourceVersion.ES_7_10:
        if input_shape_type == TransformType.INDEX:
            return es_7_10_index_knowledge
    
    return ""

def get_target_guidance(source_version: SourceVersion, target_version: TargetVersion, input_shape_type: TransformType, transform_language: TransformLanguage) -> str:
    if target_version == TargetVersion.OS_2_17:
        if input_shape_type == TransformType.INDEX:
            return os_2_17_index_guidance
    
    return ""

def get_target_knowledge(source_version: SourceVersion, target_version: TargetVersion, input_shape_type: TransformType, transform_language: TransformLanguage) -> str:
    if target_version == TargetVersion.OS_2_17:
        if input_shape_type == TransformType.INDEX:
            return os_2_17_index_knowledge
    
    return ""