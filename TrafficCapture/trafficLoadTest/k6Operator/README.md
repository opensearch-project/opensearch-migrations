# Patched k6 operator

The load-test chart uses a narrowly patched k6-operator `v1.5.0`. For local (non-Grafana-Cloud)
`TestRun` objects, the initializer writes only:

```json
{"success":true,"maxVUs":131072}
```

to `/dev/termination-log`. Kubernetes copies that value into the initializer Pod's terminated
container status, and the controller reads it from the ordinary Pod object. Local runs therefore do
not use the Kubernetes logs subresource as a control-message channel. Grafana Cloud output retains
the upstream full inspect-output/log path because its API setup consumes additional inspect fields.

The Dockerfile downloads the immutable upstream commit named in the file, verifies the source
archive SHA-256, applies the reviewable patch under `patches/`, runs the focused upstream Go tests,
and builds the controller. The source tree is intentionally not vendored.

Build this image together with the k6 runner:

```bash
./gradlew :buildImages:buildKitLoadTestAll
```

The Helm installer derives both images from the migration registry. It also accepts
`--operator-image` (or `K6_OPERATOR_IMAGE`) for an explicit controller reference.
