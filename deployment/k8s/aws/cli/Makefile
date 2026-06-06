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

# Run the full amber test suite. FORCE_TTY=1 exercises the interactive paths
# (panel/progress/dashboard render) without needing a real terminal.
test:
	MIGRATE_FORCE_TTY=1 $(AMBER) test test/

# Compile to bash-3.2 AND execute the compiled output under a real bash 3.2
# interpreter (/bin/bash on macOS). This is the contract the user asked for.
verify: build
	./test/verify-bash32.sh

clean:
	rm -rf build
