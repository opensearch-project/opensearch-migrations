# Makefile — migrate-cli build & test
#
# Targets:
#   make lint     — shellcheck on every shell file
#   make test     — bats-core tests under test/
#   make release  — produce dist/migrate-cli-<version>.tar.gz
#   make install  — install locally via install.sh INSTALL_FROM_LOCAL
#   make clean    — remove dist/

CLI_VERSION ?= 0.1.0
DIST_DIR    := dist
TARBALL     := $(DIST_DIR)/migrate-cli-$(CLI_VERSION).tar.gz
# License lives at the repo root (Apache-2.0). We pull it into the
# tarball at build time rather than vendoring a per-CLI copy.
LICENSE_SRC := $(firstword $(wildcard ../../../../LICENSE) $(wildcard $(CURDIR)/LICENSE))

SHELL_FILES := bin/migration-assistant install.sh $(wildcard lib/*.sh) $(wildcard test/helpers/*.sh)

.PHONY: lint lint-bash32 test release install clean help

help:
	@printf 'Targets:\n  make lint\n  make test\n  make release\n  make install\n  make clean\n'

lint: lint-bash32
	@command -v shellcheck >/dev/null 2>&1 || { echo "install shellcheck"; exit 127; }
	shellcheck -x -P lib $(SHELL_FILES)

# bash-3.2 floor enforcement. Run as part of `make lint` so PRs that
# introduce a bash 4+ feature fail before merge.
lint-bash32:
	./test/lint-bash32.sh

test:
	@command -v bats >/dev/null 2>&1 || { echo "install bats-core: brew install bats-core"; exit 127; }
	# Use the wrapper so silent test-count mismatches (caused by `[[…]]
	# && fn` rc-leaks under bats's set -e + errtrace) surface as a hard
	# failure with diagnostics, instead of being buried in a single-line
	# warning that returns exit 0 in some bats versions.
	./test/run-bats.sh

$(TARBALL): | $(DIST_DIR)
	@[ -n "$(LICENSE_SRC)" ] || { echo "could not locate LICENSE (looked at ../../../../LICENSE and ./LICENSE)"; exit 1; }
	@stage=$$(mktemp -d) && \
	    mkdir -p "$$stage/migrate-cli-$(CLI_VERSION)" && \
	    cp -R bin lib skills install.sh README.md "$$stage/migrate-cli-$(CLI_VERSION)/" && \
	    cp "$(LICENSE_SRC)" "$$stage/migrate-cli-$(CLI_VERSION)/LICENSE" && \
	    tar -czf $@ -C "$$stage" "migrate-cli-$(CLI_VERSION)" && \
	    rm -rf "$$stage" && \
	    printf 'wrote %s\n' $@

$(DIST_DIR):
	mkdir -p $@

release: $(TARBALL)

install: release
	INSTALL_FROM_LOCAL=$(CURDIR) bash install.sh

clean:
	rm -rf $(DIST_DIR)
