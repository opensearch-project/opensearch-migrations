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

SHELL_FILES := bin/migration-assistant install.sh $(wildcard lib/*.sh) $(wildcard test/helpers/*.sh)

.PHONY: lint test release install clean help

help:
	@printf 'Targets:\n  make lint\n  make test\n  make release\n  make install\n  make clean\n'

lint:
	@command -v shellcheck >/dev/null 2>&1 || { echo "install shellcheck"; exit 127; }
	shellcheck -x -P lib $(SHELL_FILES)

test:
	@command -v bats >/dev/null 2>&1 || { echo "install bats-core: brew install bats-core"; exit 127; }
	bats test/

$(TARBALL): | $(DIST_DIR)
	@stage=$$(mktemp -d) && \
	    mkdir -p "$$stage/migrate-cli-$(CLI_VERSION)" && \
	    cp -R bin lib skills install.sh README.md LICENSE "$$stage/migrate-cli-$(CLI_VERSION)/" && \
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
