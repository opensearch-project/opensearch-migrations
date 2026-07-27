# k6 load-test image for the Kubernetes/Argo integration.
#
# Bakes the scenario scripts, shared libs, per-scenario data, and the named
# load-profile env files into /scripts, so a run is fully specified by two
# names: the scenario (script) and the configName (env file).
#
# Built by buildImages/build.gradle (buildKitProjects entry "k6") and pushed as
# migrations/k6. BASE_IMAGE is rewritten through the pull-through cache by the
# build, defaulting to the upstream grafana/k6 image.
ARG BASE_IMAGE=docker.io/grafana/k6:latest
FROM ${BASE_IMAGE}

# grafana/k6 runs as the unprivileged "k6" user; COPY as root leaves the files
# world-readable, thus can be consumed by the k6 process.
COPY scenarios/ /scripts/scenarios/
COPY lib/       /scripts/lib/
COPY data/      /scripts/data/
COPY k6-config/ /scripts/k6-config/

# The image keeps grafana/k6's default ENTRYPOINT (["k6"]). The Argo
# WorkflowTemplate overrides the command with a small /bin/sh wrapper that
# sources the selected env file, applies per-run overrides, then execs k6.
