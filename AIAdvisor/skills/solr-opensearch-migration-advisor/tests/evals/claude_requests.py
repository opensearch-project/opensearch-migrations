from claude_agent_sdk import query, ClaudeAgentOptions, ResultMessage
import os

script_path = "/".join(os.path.realpath(__file__).split("/")[:-1])
cwd = f"{script_path}/../../../solr-opensearch-migration-advisor"


async def call_api(prompt: str, options: dict, context: dict) -> dict:
    # check if test has continue flag set, and only then continue sessions
    continue_conversation = (context.get("test", {}).get("metadata", {}).get("continue", False))
    agent_options = ClaudeAgentOptions(
        # picks up most revent conversation (allows sequential tests, would fail on parallelized)
        continue_conversation=continue_conversation,
        allowed_tools=["Read", "Edit", "Glob", "Grep", "Skill", "WebFetch"],  # Tools Claude can use
        permission_mode="acceptEdits",  # Auto-approve file edits
        setting_sources=["project"],  # for paths: https://platform.claude.com/docs/en/agent-sdk/skills
        effort="medium",
        cwd=cwd
    )
    async for message in query(
            prompt=prompt,
            options=agent_options
    ):
        if isinstance(message, ResultMessage):
            return {"output": message.result}
