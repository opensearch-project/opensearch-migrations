#!/usr/bin/env bats
# test_crane_dst.bats — destination URL + ECR repo name layout.
#
# Operator failure caught: every crane copy failed with a generic error
# because we were pushing to <ecr-host>/<single-repo>/<deep>/<path>:tag
# where the deep-path repo didn't exist. ECR repo names allow `/` but
# each must be created. Upstream aws-bootstrap.sh uses `mirrored/<image-no-tag>`
# as the repo name; we match that exactly.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh cfn.sh crane.sh
  log_init
}

teardown() {
  teardown_isolated_home
}

# ---------- _dst_for ----------

@test "_dst_for produces <ecr-host>/mirrored/<original-image>:<tag>" {
  local out
  out=$(_dst_for 'quay.io/strimzi/kafka:0.50.1-kafka-4.0.0' '629003556176.dkr.ecr.us-east-1.amazonaws.com')
  [ "$out" = '629003556176.dkr.ecr.us-east-1.amazonaws.com/mirrored/quay.io/strimzi/kafka:0.50.1-kafka-4.0.0' ]
}

@test "_dst_for handles registry.k8s.io paths" {
  local out
  out=$(_dst_for 'registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.15.0' 'r.example.com')
  [ "$out" = 'r.example.com/mirrored/registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.15.0' ]
}

@test "_dst_for handles public.ecr.aws paths" {
  local out
  out=$(_dst_for 'public.ecr.aws/aws-controllers-k8s/cloudwatch-controller:1.4.2' 'r.example.com')
  [ "$out" = 'r.example.com/mirrored/public.ecr.aws/aws-controllers-k8s/cloudwatch-controller:1.4.2' ]
}

# ---------- _ecr_repo_for ----------

@test "_ecr_repo_for strips the tag and prepends mirrored/" {
  local out
  out=$(_ecr_repo_for 'quay.io/strimzi/kafka:0.50.1-kafka-4.0.0')
  [ "$out" = 'mirrored/quay.io/strimzi/kafka' ]
}

@test "_ecr_repo_for handles paths with multiple slashes" {
  local out
  out=$(_ecr_repo_for 'registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.15.0')
  [ "$out" = 'mirrored/registry.k8s.io/kube-state-metrics/kube-state-metrics' ]
}

@test "_ecr_repo_for handles single-segment image (rare)" {
  local out
  out=$(_ecr_repo_for 'busybox:latest')
  [ "$out" = 'mirrored/busybox' ]
}

# ---------- consistency: dst path == <ecr-host>/<ecr_repo>:<tag> ----------

@test "_dst_for and _ecr_repo_for agree on the path" {
  local src='quay.io/strimzi/kafka:0.50.1-kafka-4.0.0'
  local dst repo tag
  dst=$(_dst_for "$src" 'r.example.com')
  repo=$(_ecr_repo_for "$src")
  tag="${src##*:}"
  [ "$dst" = "r.example.com/${repo}:${tag}" ]
}
