#!/bin/bash
# see here: https://github.com/anthropics/claude-code/issues/8938; needed to adjust to allow
# circumventing intro instructions when passing outh key to enable direct start without
# answering setup questions
echo "$(jq '. += {"hasCompletedOnboarding": true}' ~/.claude.json)" > ~/.claude.json

ln -s /home/user/claude/.claude/skills/solr-opensearch-migration-advisor /home/user/claude/processing/.claude/skills/solr-opensearch-migration-advisor 2>/dev/null
ln -s /home/user/claude/references /home/user/claude/processing/references 2>/dev/null
ln -s /home/user/claude/scripts /home/user/claude/processing/scripts 2>/dev/null
ln -s /home/user/claude/steering /home/user/claude/processing/steering 2>/dev/null

cd claude/processing || exit
claude
