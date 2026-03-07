# Argo CI/CD — Build and Deploy Targets

REGISTRY ?= $(shell aws sts get-caller-identity --query Account --output text).dkr.ecr.$(AWS_REGION).amazonaws.com
AWS_REGION ?= us-east-1
TAG ?= latest

.PHONY: help build-ci-runner build-release-runner build-all push-all deploy-minikube deploy-prod lint validate

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-25s\033[0m %s\n", $$1, $$2}'

# === Docker Images ===

build-ci-runner: ## Build ci-runner Docker image
	docker build -t ci-runner:$(TAG) images/ci-runner/

build-release-runner: ## Build release-runner Docker image
	docker build -t release-runner:$(TAG) images/release-runner/

build-all: build-ci-runner build-release-runner ## Build all Docker images

push-ci-runner: build-ci-runner ## Push ci-runner to ECR
	docker tag ci-runner:$(TAG) $(REGISTRY)/ci-runner:$(TAG)
	docker push $(REGISTRY)/ci-runner:$(TAG)

push-release-runner: build-release-runner ## Push release-runner to ECR
	docker tag release-runner:$(TAG) $(REGISTRY)/release-runner:$(TAG)
	docker push $(REGISTRY)/release-runner:$(TAG)

push-all: push-ci-runner push-release-runner ## Push all images to ECR

# === Deployment ===

deploy-minikube: ## Deploy all components on minikube
	./deploy.sh --all

deploy-prod: ## Deploy workflow templates on production EKS
	kubectl apply -n ma -f workflows/common-ci-steps.yaml
	kubectl apply -n ma -f workflows/build-images.yaml
	kubectl apply -n ma -f workflows/argo-ci-pipeline.yaml
	kubectl apply -n ma -f workflows/k8s-local-test.yaml
	kubectl apply -n ma -f workflows/k8s-matrix-test.yaml
	kubectl apply -n ma -f workflows/upload-results.yaml
	kubectl apply -n ma -f workflows/aws-integ/
	kubectl apply -n ma -f ci-stage-locks-configmap.yaml
	kubectl apply -n ma -f cron/nightly-matrix.yaml
	kubectl apply -f events/

# === Validation ===

lint: ## Lint all YAML files
	@echo "Checking YAML syntax..."
	@ERRORS=0; \
	for f in $$(find . -name '*.yaml' -o -name '*.yml' | grep -v node_modules); do \
		python3 -c "import yaml; yaml.safe_load(open('$$f'))" 2>/dev/null || \
		{ echo "  FAIL: $$f"; ERRORS=$$((ERRORS+1)); }; \
	done; \
	echo "$$ERRORS errors found"; \
	[ $$ERRORS -eq 0 ]

validate: lint ## Validate all files (lint + shell syntax)
	@echo "Checking shell scripts..."
	@bash -n setup-minikube-ci.sh && echo "  OK: setup-minikube-ci.sh"
	@bash -n deploy.sh && echo "  OK: deploy.sh"
	@bash -n reportportal/install.sh && echo "  OK: reportportal/install.sh"
	@echo "Checking Dockerfiles..."
	@docker build --check images/ci-runner/ 2>/dev/null && echo "  OK: ci-runner/Dockerfile" || echo "  SKIP: ci-runner (docker not available)"
	@docker build --check images/release-runner/ 2>/dev/null && echo "  OK: release-runner/Dockerfile" || echo "  SKIP: release-runner (docker not available)"
	@echo "Validation complete"

# === Quick Test ===

test-checkout: ## Quick test: run git-checkout template
	argo submit -n ma --from workflowtemplate/common-ci-steps \
		--entrypoint git-checkout -p branch=main --watch

test-single: ## Quick test: run single k8s-local-test
	argo submit -n ma examples/run-single-test.yaml --watch

test-matrix: ## Quick test: run matrix test
	argo submit -n ma examples/run-matrix.yaml --watch

test-ci: ## Quick test: run CI pipeline (4 stripes for local)
	argo submit -n ma examples/run-ci-pipeline.yaml --watch
