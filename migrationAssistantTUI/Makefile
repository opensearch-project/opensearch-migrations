# migrationAssistantTUI — make targets
#
# All targets run with CGO_ENABLED=0 to keep binaries static and the test runner
# free of host C-library coupling. Test timeout is 2m with -race per design §5.

GO         ?= go
PKG        := ./...
CGO        := CGO_ENABLED=0
TEST_FLAGS := -race -timeout=2m -count=1
BIN_DIR    := bin
BIN_NAME   := migration-assistant
LDFLAGS    := -s -w \
              -X main.Version=$(shell git describe --tags --always --dirty 2>/dev/null || echo dev) \
              -X main.MAVersion=$(shell cat MA_VERSION 2>/dev/null || echo 3.2.1)
TESTDATA   := internal

.PHONY: all build test lint vet fmt fmt-check tidy testdata-budget snapshot clean

all: lint vet test build

build:
	@mkdir -p $(BIN_DIR)
	$(CGO) $(GO) build -trimpath -ldflags '$(LDFLAGS)' -o $(BIN_DIR)/$(BIN_NAME) ./cmd/tui

test:
	$(CGO) $(GO) test $(TEST_FLAGS) $(PKG)

vet:
	$(GO) vet $(PKG)

fmt:
	$(GO) fmt $(PKG)

fmt-check:
	@out=$$($(GO) fmt $(PKG)); \
	if [ -n "$$out" ]; then \
	  echo "gofmt produced changes:"; echo "$$out"; exit 1; \
	fi

lint:
	@command -v golangci-lint >/dev/null 2>&1 || { \
	  echo "golangci-lint not installed — see https://golangci-lint.run/welcome/install/"; \
	  exit 1; }
	golangci-lint run $(PKG)

tidy:
	$(GO) mod tidy

# PLAN §11.3: testdata budget ≤ 1 MB across all packages.
testdata-budget:
	@total=$$(find $(TESTDATA) -type d -name testdata -exec du -sb {} + 2>/dev/null | awk '{s+=$$1} END {print s+0}'); \
	limit=1048576; \
	echo "testdata: $$total bytes (limit $$limit)"; \
	if [ "$$total" -gt "$$limit" ]; then \
	  echo "FAIL: testdata exceeds 1 MB budget"; exit 1; \
	fi

# Update golden snapshots intentionally; review the diff before committing.
snapshot:
	$(CGO) $(GO) test $(TEST_FLAGS) -run . -update $(PKG)

clean:
	rm -rf $(BIN_DIR)
	$(GO) clean -testcache
