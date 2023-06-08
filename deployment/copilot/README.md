### Copilot Deployment
Copilot is a tool for deploying containerized applications on AWS ECS. Official documentation can be found [here](https://aws.github.io/copilot-cli/docs/overview/).

### Initial Setup
When initially setting up Copilot; apps, services, and environments need to be initialized. If an existing `manifest.yml` is present (as is the case here), that configuration will be picked up and used after preliminary input is given.

If using temporary environment credentials, any input can be given to prompt, but be sure to specify the proper region when asked.

```
// Initialize app
copilot app init

// Initialize env
copilot env init --name test

// Initialize services
copilot svc init --name kafka-puller
copilot svc init --name traffic-replayer
copilot svc init --name traffic-comparator
copilot svc init --name traffic-comparator-jupyter

```

### Deploying Services to an Environment

```
// Deploy service to a configured environment
copilot svc deploy --name traffic-comparator --env test
```

### Executing commands on a deployed Service

Commands can be executed on a service if that service has enabled `exec: true` in their `manifest.yml` and the SSM Session Manager plugin is installed when prompted.
```
copilot svc exec traffic-comparator --container traffic-comparator --command "bash"
```