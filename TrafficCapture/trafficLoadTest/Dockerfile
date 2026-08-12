# k6 load-test SCRIPTS image — data only, nothing runnable.
#
# the k6LoadTest chart mounts it at /scripts on both the TestRun initializer
# and its runner pods, which run the official grafana/k6 image.
#
# A run is then fully specified by two names — the scenario (TestRun
# spec.script.localFile=/scripts/scenarios/<scenario>.js) and the preset (K6_PRESET=<name>,
# resolved from /scripts/k6-config/<name>.env by lib/config.js) — with no scenario content in any
# ConfigMap and no custom k6 build.
FROM scratch

# Copied explicitly rather than `COPY . /` so the image root holds exactly the four directories the
# scenarios resolve against.
COPY scenarios/ /scenarios/
COPY lib/       /lib/
COPY data/      /data/
COPY k6-config/ /k6-config/
