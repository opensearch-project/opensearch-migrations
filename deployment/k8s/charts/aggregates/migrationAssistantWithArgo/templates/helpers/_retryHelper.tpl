{{/*
  Retry Helper — bounded per-step retry for transient failures.

  Defines `retry`, used by install/uninstall/s3 jobs to wrap individual
  network or API calls that occasionally flake (ECR throttling, DNS, helm
  registry resolution, LocalStack startup, transient API server hiccups).

  Per-step retry is preferred over Job-level backoffLimit because it isolates
  the failure to the step that flaked, instead of restarting the whole pod
  and replaying every prior step.
*/}}
{{- define "migration.retryFunctions" -}}
# retry <max_attempts> <sleep_seconds> <description> -- <command...>
# Wrap pipes/redirects in a shell function and pass that function name.
retry() {
  _retry_max=$1
  _retry_sleep=$2
  _retry_desc=$3
  shift 3
  if [ "$1" = "--" ]; then shift; fi
  _retry_i=1
  while true; do
    if "$@"; then
      return 0
    fi
    if [ "$_retry_i" -ge "$_retry_max" ]; then
      echo "FAIL: $_retry_desc — exhausted $_retry_max attempts" >&2
      return 1
    fi
    echo "Transient: $_retry_desc failed (attempt $_retry_i/$_retry_max), sleeping ${_retry_sleep}s..." >&2
    sleep "$_retry_sleep"
    _retry_i=$((_retry_i + 1))
  done
}
{{- end -}}
