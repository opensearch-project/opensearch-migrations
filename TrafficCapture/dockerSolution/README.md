# Overview

To use launch the end-2-end (E2E) services in containers, simply run `./gradlew :dockerSolution:composeUp` from the
TrafficCapture directory (parent of this directory). That will build all java artifacts and create the necessary images
for each service.  `./gradlew :dockerSolution:composeDown` will tear everything (volumes, networks, containers) back
down again.

Notice that most of the Dockerfiles are dynamically constructed in the build hierarchy. Some efforts have been made
to ensure that changes will make it into containers to be launched.

If a user wants to use their own checkout of the traffic-comparator repo, just set the environment variable "
TRAFFIC_COMPARATOR_DIRECTORY" to the directory that contains `setup.py`. Otherwise, if that isn't set, the traffic
comparator repo will be checked out to the build directory and that will be used. Notice that the checkout happens when
the directory wasn't present and there wasn't an environment variable specifying a directory. Once a directory exists,
it will be mounted to the traffic-comparator and jupyter services.

Netcat is still used to connect several of the components and we're still working on improving the resiliency story
between these containers. The long term approach is to replace fixed streams with message bus approaches directly (i.e.
Kafka).  In the short term, we can and are beginning, to leverage things like conditions on dependent services.