# amber-tui — build + test the Amber rewrite of the migration-assistant TUI.
#
# Requires: amber 0.6.0-alpha  (brew install amber-lang/amber/amber-lang)
AMBER  ?= amber
TARGET ?= bash-3.2
SRC    := $(wildcard src/*.ab)
OUT    := $(patsubst src/%.ab,build/%.sh,$(SRC))

.PHONY: all build check test verify clean

# Full gate: parse-check, compile to bash-3.2, run amber tests, then prove the
# compiled output runs on a genuine bash 3.2 interpreter.
all: check build test verify

build:
	@mkdir -p build

# Type/parse-check every source module.
check:
	@for f in $(SRC); do echo "check $$f"; $(AMBER) check $$f || exit 1; done

# Compile each module to bash for the macOS 3.2 floor.
build-all: build $(OUT)
build/%.sh: src/%.ab | build
	$(AMBER) build --target $(TARGET) $< $@

# Run the full amber test suite — ONE file per invocation. `amber test test/`
# (all files in one process) is FORBIDDEN as the gate: 0.6 runs every block in a
# single shared-process parallel pool where env_var_set leaks, so the per-world
# MIGRATE_HOME isolation races across files (~114 spurious cold failures). One
# invocation per file keeps each file's blocks racing only against their own
# mktemp-unique siblings → deterministic. (See AMBER_IDIOMS.md "Test-runner
# parallelism".) FORCE_TTY=1 exercises the interactive render paths sans a TTY.
test:
	@fail=0; for f in test/test_*.ab; do \
		echo "test $$f"; \
		MIGRATE_FORCE_TTY=1 $(AMBER) test "$$f" || fail=1; \
	done; exit $$fail

# Compile to bash-3.2 AND execute the compiled output under a real bash 3.2
# interpreter (/bin/bash on macOS). This is the contract the user asked for.
verify: build
	./test/verify-bash32.sh

clean:
	rm -rf build
