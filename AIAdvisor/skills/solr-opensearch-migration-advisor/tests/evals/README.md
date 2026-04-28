### Eval Notes

- General docs:
  - python sdk: https://platform.claude.com/docs/en/agent-sdk/quickstart#python-(uv)
  - listing of session handling: https://platform.claude.com/docs/en/agent-sdk/sessions
- Claude usage requires api token.
  If you have a claude code subscription plan, you can just create a token there via:
  - `claude setup-token`
  - `export CLAUDE_CODE_OAUTH_TOKEN=<token>` (or put in .env file, will be picked up by the claude_requests.py)
    - see also `https://github.com/anthropics/claude-agent-sdk-python/issues/559`
- Sequential tests need setting of `continue=true` metadata, otherwise each request starts a new session.
- for reading Skills from filesystem, agent needs corresponding settings on client init: `https://platform.claude.com/docs/en/agent-sdk/skills`
  - `setting_sources=["user", "project"]`
  - adding "Skills" to `allowed_tools` setting
- setting project in above setting_resources should allow Claude to look within configured cwd for folder `.claude/skills/` with SKILL.md,
  no clear way to configure the path to custom as in our case
- there could be possibility for renaming sessions to make them usable in tests, check `https://github.com/anthropics/claude-code/issues/2112`


### PYTHON ENV SETUP
- Run uv sync with eval extras: `uv sync --extra eval` 
- `source .venv/bin/activate`
- copy the `.env.example` file to `.env` file and fill in properties
- run evals: `./scripts/run_evals.sh`


### PROMPTFOO LLM JUDGE PARSING ERRORS
- sometimes you might see the error message "Could not extract JSON from llm-rubric response". This means that
  the judge does not return valid json. To alleviate this two steps:
  - prefix `Give your evaluation response in json format, and properly escape chars where this is needed.` to the rubric
  - add response_format in llm judge config, such as:
  ```yaml
  defaultTest:
    options:
      provider:
        id: bedrock:us.anthropic.claude-sonnet-4-5-20250929-v1:0
        config:
          region: us-east-1
          max_tokens: 256
          # Prohibit error messages 'Could not extract JSON from llm-rubric response':
          # https://github.com/promptfoo/promptfoo/issues/2084
          response_format: json_object
  ```


