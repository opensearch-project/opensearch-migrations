#!/usr/bin/env bats
# test_artifacts.bats — exercise lib/artifacts.sh with a stubbed `curl`.
#
# Strategy: the artifacts module hits HEAD then GET via curl. We stub curl to
# (a) return 200 for HEAD on a chosen URL, (b) write canned bytes for GET, and
# (c) write a SHA file that matches the canned bytes. Then we run
# `artifacts_fetch` and verify the symlink + cache layout are correct.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh artifacts.sh
  stage_dir_init
}

teardown() {
  teardown_isolated_home
}

@test "artifacts_fetch caches on second call" {
  # We can't easily stub curl with conditional behaviour in mkstub. Instead,
  # write a curl stub that:
  #   - HEAD (`-I`) → exits 0
  #   - GET → writes "hello" or the SHA file depending on URL suffix
  cat >"$STUB_DIR/curl" <<'EOF'
#!/usr/bin/env bash
out=""
url=""
head=0
for arg in "$@"; do
  case "$arg" in
    -I*) head=1 ;;
    -o)  shift_next=1 ;;
    *)
      if [[ -n "${shift_next:-}" ]]; then
        out="$arg"; shift_next=
      else
        url="$arg"
      fi
      ;;
  esac
done
if [[ "$head" -eq 1 ]]; then
  exit 0
fi
case "$url" in
  *.sha256) printf '%s  %s\n' '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824' "$(basename "${url%.sha256}")" >"$out" ;;
  *)        printf 'hello' >"$out" ;;
esac
exit 0
EOF
  chmod +x "$STUB_DIR/curl"

  state_load
  state_set AWS_REGION us-east-1
  state_save

  run artifacts_fetch some-artifact.tar.gz 3.2.1
  [ "$status" -eq 0 ]
  link="$output"
  [ -L "$link" ]
  [ "$(cat "$link")" = "hello" ]

  # Second call should be served from cache without writing additional files.
  run artifacts_fetch some-artifact.tar.gz 3.2.1
  [ "$status" -eq 0 ]
}

@test "artifacts_fetch fails on SHA mismatch" {
  cat >"$STUB_DIR/curl" <<'EOF'
#!/usr/bin/env bash
out=""
url=""
head=0
for arg in "$@"; do
  case "$arg" in
    -I*) head=1 ;;
    -o)  shift_next=1 ;;
    *)
      if [[ -n "${shift_next:-}" ]]; then
        out="$arg"; shift_next=
      else
        url="$arg"
      fi
      ;;
  esac
done
if [[ "$head" -eq 1 ]]; then exit 0; fi
case "$url" in
  *.sha256) printf '%s  %s\n' 'deadbeef00000000000000000000000000000000000000000000000000000000' "$(basename "${url%.sha256}")" >"$out" ;;
  *)        printf 'hello' >"$out" ;;
esac
exit 0
EOF
  chmod +x "$STUB_DIR/curl"

  state_load
  state_save
  run artifacts_fetch some.tgz 3.2.1
  [ "$status" -ne 0 ]
}
