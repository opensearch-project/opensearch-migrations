.PHONY: help list build-all test-all clean
.DEFAULT_GOAL := help

SHELL := /bin/bash
IMAGE_PREFIX ?= custom-elasticsearch
MAJORS := 1 2 5 6 7 8

help: ## Show this help
	@grep -E '^[a-zA-Z0-9._-]+:.*##' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

list: ## List all available versions
	@./build.sh --list

build-all: ## Build all ES versions
	./build.sh --all

test-all: ## Test all ES versions
	./test.sh --all

# Per-major targets: build-1, build-2, build-5, build-6, build-7, build-8
$(foreach m,$(MAJORS),build-$(m)): build-%: ## Build all minor versions for major version %
	./build.sh --major $*

# Per-major test targets
$(foreach m,$(MAJORS),test-$(m)): test-%: ## Test all minor versions for major version %
	./test.sh --major $*

# Build + test per major
$(foreach m,$(MAJORS),bt-$(m)): bt-%: build-% test-% ## Build and test major version %

bt-all: build-all test-all ## Build and test all versions

clean: ## Remove all custom-elasticsearch images
	@docker images --format '{{.Repository}}:{{.Tag}}' | grep '^$(IMAGE_PREFIX):' | \
		xargs -r docker rmi -f
	@echo "Cleaned all $(IMAGE_PREFIX) images"
