# Migration Console Helm Chart
The component Helm chart for the Migration Console

### Developing the Migration Console
To enable quicker development with libraries on the Migration Console, such as the `console_link` and `integ_test`, a `developerModeEnabled` setting was
added to the default `values.yaml` in this directory. When this setting is enabled in the `values.yaml`, the Migration Console container will
mount the local repository onto the container, such that changes to these libraries will be automatically updated on the Migration Console container,
enabling a quicker feedback loop when testing these libraries in a deployed environment.