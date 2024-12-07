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


def perform_inference(llm: Runnable[LanguageModelInput, BaseMessage], batched_tasks: List[InferenceTask]) -> List[InferenceResult]:
    return asyncio.run(_perform_async_inference(llm, batched_tasks))

# Inference APIs can be throttled pretty aggressively.  Performing them as a batch operation can help with increasing
# throughput. Ideally, we'd be using Bedrock's batch inference API, but Bedrock's approach to that is an asynchronous
# process that writes the results to S3 and returns a URL to the results.  This is not implemented by default in the
# ChatBedrockConverse class, so we'll skip true batch processing for now.  Instead, we'll just perform the inferences in
# parallel with aggressive retry logic.
async def _perform_async_inference(llm: Runnable[LanguageModelInput, BaseMessage], batched_tasks: List[InferenceTask]) -> List[InferenceResult]:
    async_responses = [llm.ainvoke(task.context) for task in batched_tasks]
    responses = await asyncio.gather(*async_responses)

    return [InferenceResult(transform_id=task.transform_id, response=response) for task, response in zip(batched_tasks, responses)]
    