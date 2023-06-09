### Copilot Deployment
Copilot is a tool for deploying containerized applications on AWS ECS. Official documentation can be found [here](https://aws.github.io/copilot-cli/docs/overview/).

### Initial Setup

#### Setting up Copilot CLI
If you are on Mac the following Homebrew command can be ran to set up the Copilot CLI:
```
brew install aws/tap/copilot-cli
```
Otherwise please follow the manual instructions [here](https://aws.github.io/copilot-cli/docs/getting-started/install/)

#### Setting up existing Copilot infrastructure

When initially setting up Copilot; apps, services, and environments need to be initialized. Although a bit confusing, when initializing already defined (having a `manifest.yml`) environments and resources, Copilot will still prompt for input which will ultimately get ignored in favor of the existing `manifest.yml` file.

If using temporary environment credentials, any input can be given to prompt, but be sure to specify the proper region when asked.

```
// Initialize app (may not be necessary need to verify with clean slate)
copilot app init

// Initialize env
// If using temporary credentials, when prompted select 'Temporary Credentials' and press `enter` for each default value as these can be ignored
// Be cautious to specify the proper region as this will dictate where resources are deployed
copilot env init --name test

// Initialize services
copilot svc init --name kafka-puller
copilot svc init --name traffic-replayer
copilot svc init --name traffic-comparator
copilot svc init --name traffic-comparator-jupyter

```

### Deploying Services to an Environment
Currently, it seems that Copilot does not support deploying all services at once or creating dependencies between separate services. In light of this, services need to be deployed one at a time as show below.

**Note**: Currently these services require an existing MSK instance and EFS filesystem to be functional, but this is subject to change

```
// Deploy service to a configured environment
copilot svc deploy --name traffic-comparator-jupyter --env test
copilot svc deploy --name traffic-comparator --env test
copilot svc deploy --name traffic-replayer --env test
copilot svc deploy --name kafka-puller --env test
```

### Executing Commands on a Deployed Service

Commands can be executed on a service if that service has enabled `exec: true` in their `manifest.yml` and the SSM Session Manager plugin is installed when prompted.
```
copilot svc exec traffic-comparator --container traffic-comparator --command "bash"
```

### Useful Commands

`copilot app show`: Provides details on the current app \
`copilot svc show`: Provides details on a particular service
