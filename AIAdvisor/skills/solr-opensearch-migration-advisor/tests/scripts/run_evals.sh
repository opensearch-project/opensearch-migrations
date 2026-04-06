#!/bin/bash

set -o pipefail

SCRIPT_DIR="$(dirname "$0")"
source "$SCRIPT_DIR"/../../.env

# creating symlink to path where claude sdk expects the skills
mkdir -p "$SCRIPT_DIR"/../../.claude/skills/migration-advisor
ln -s "$SCRIPT_DIR"/../../SKILL.md "$SCRIPT_DIR"/../../.claude/skills/migration-advisor/SKILL.md

AWS_REGION=$AWS_REGION \
AWS_DEFAULT_REGION=$AWS_DEFAULT_REGION \
AWS_BEARER_TOKEN_BEDROCK=$AWS_BEARER_TOKEN_BEDROCK \
BEDROCK_INFERENCE_PROFILE_ARN=$BEDROCK_INFERENCE_PROFILE_ARN \
promptfoo eval -c $SCRIPT_DIR/../evals/eval.yaml --no-cache --max-concurrency 1