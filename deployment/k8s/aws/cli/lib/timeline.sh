#!/usr/bin/env bash
# timeline.sh — vertical phase checklist for the resume picker.
#
# Replaces the resume one-liner ("previous run progressed to: helm_done")
# with a visual checklist that shows ALL phases at once, so the operator
# sees what's done, what's running, and what's next in a single glance.
#
# Status markers:
#   ●  done       — phase completed (state.last_step has advanced past it)
#   ◐  running    — phase the previous run was inside when it stopped
#   ○  pending    — phase the previous run hasn't reached
#   ✗  failed     — phase that aborted (last_step has the special "_failed" suffix)
#
# Used by lib/resume.sh as the visual context for the resume prompt.
# Standalone helper — pure render + return; no I/O beyond stderr.
#
# Phase ordering matches manual_path() in resume.sh + the last_step values
# written across cfn.sh / helm.sh / agent.sh.

[[ -n "${__MIGRATE_TIMELINE_LOADED:-}" ]] && return 0
__MIGRATE_TIMELINE_LOADED=1

# The canonical phase list. Keys are the last_step values; labels are the
# operator-visible names. Order is the deploy order.
__TIMELINE_PHASES=(
  "discover|Discover environment"
  "wizard_done|Configure deploy"
  "cfn_done|Deploy CloudFormation"
  "kubeconfig|Set kubeconfig"
  "build_done|Build images (optional)"
  "crane_done|Mirror images (optional)"
  "helm_done|Install helm chart"
  "ready|Ready"
  "console_handoff|Console (Manual mode)"
  "agent_handoff|Agent handoff (AI mode)"
)

# timeline_render <last_step>
# Print a vertical checklist. <last_step> is the state.env value
# (state_resumable_step output).
#
# Falls back to a plain text list when not interactive.
timeline_render() {
  local last="$1"

  # Find the index of last_step in __TIMELINE_PHASES. -1 if absent.
  local last_idx=-1 i n entry key
  n=${#__TIMELINE_PHASES[@]}
  for ((i = 0; i < n; i++)); do
    entry="${__TIMELINE_PHASES[$i]}"
    key="${entry%%|*}"
    if [[ "$key" == "$last" ]]; then
      last_idx=$i
      break
    fi
  done

  for ((i = 0; i < n; i++)); do
    entry="${__TIMELINE_PHASES[$i]}"
    key="${entry%%|*}"
    local label="${entry#*|}"
    local marker color suffix=""
    if (( i < last_idx )); then
      marker='●'; color="$__UI_C_GREEN";  suffix=' done'
    elif (( i == last_idx )); then
      marker='◐'; color="$__UI_C_YELLOW"; suffix=' last'
    else
      marker='○'; color="$__UI_C_DIM";    suffix=''
    fi
    printf '  %s%s%s  %s%s%s\n' \
      "$color" "$marker" "$__UI_C_RESET" \
      "$label" \
      "$__UI_C_DIM" "$suffix" \
      >&2
    # Reset SGR at end of line so background apps don't inherit dim.
    printf '%s' "$__UI_C_RESET" >&2
  done
}

# timeline_phase_label <key>
# Echo the operator-visible label for a last_step value, or the key
# itself if unknown.
timeline_phase_label() {
  local key="$1" entry
  for entry in "${__TIMELINE_PHASES[@]}"; do
    if [[ "${entry%%|*}" == "$key" ]]; then
      printf '%s' "${entry#*|}"
      return
    fi
  done
  printf '%s' "$key"
}
