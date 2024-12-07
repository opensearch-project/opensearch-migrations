import asyncio
from dataclasses import dataclass
import importlib
import logging
import os
from typing import Any, Callable, Dict, List

from langchain_core.language_models import LanguageModelInput
from langchain_core.messages import BaseMessage

from transform_expert.utils.inference import InferenceTask, perform_inference


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
    transform: Transform = None
    output: str = "bleh"

    def to_json(self) -> Dict[str, Any]:
        return {
            "transform_id": self.transform_id,
            "context": [turn.to_json() for turn in self.context],
            "transform": self.transform.to_json() if self.transform else None,
            "output": self.output if self.output else None
        }
    
    def to_inference_task(self) -> InferenceTask:
        return InferenceTask(
            transform_id=self.transform_id,
            context=self.context
        )

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