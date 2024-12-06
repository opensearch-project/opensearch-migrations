import asyncio
from dataclasses import dataclass
from typing import List

from langchain_core.language_models import LanguageModelInput
from langchain_core.messages import BaseMessage
from langchain_core.runnables import Runnable

@dataclass
class InferenceTask:
    transform_id: str
    context: List[BaseMessage]
    llm: Runnable[LanguageModelInput, BaseMessage]

    def to_json(self) -> dict:
        return {
            "transform_id": self.transform_id,
            "context": [turn.to_json() for turn in self.context]
        }

@dataclass
class InferenceResult:
    transform_id: str
    response: BaseMessage

    def to_json(self) -> dict:
        return {
            "transform_id": self.transform_id,
            "response": self.response.to_json()
        }

async def perform_async_inference(batched_tasks: List[InferenceTask]) -> List[InferenceResult]:
    async_responses = [task.llm.ainvoke(task.context) for task in batched_tasks]
    responses = await asyncio.gather(*async_responses)

    return [InferenceResult(transform_id=task.transform_id, response=response) for task, response in zip(batched_tasks, responses)]
    