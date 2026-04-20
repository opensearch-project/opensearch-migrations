# see here: https://github.com/anthropics/claude-code/issues/8938; needed to adjust to allow
# circumventing intro instructions when passing outh key to enable direct start without
# answering setup questions
echo "$(jq '. += {"hasCompletedOnboarding": true}' ~/.claude.json)" > ~/.claude.json

cd claude/processing || exit
claude