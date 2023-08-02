# Overview

To launch the end-2-end (E2E) services in containers, simply run `./gradlew :dockerSolution:composeUp` from the
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

### Running the Docker Solution

While in the TrafficCapture directory, run the following command:

`./gradlew :dockerSolution:composeUp`

### Compatibility

The tools in this directory can only be built if you have Java version 11 installed.

The version is specified in `TrafficCapture/build.gradle` using a Java toolchain, which allows us
to decouple the Java version used by Gradle itself from Java version used by the tools here.

Any attempt to use a different version will cause the build to fail and will result in the following error (or similar)
depending on which tool/project is being built. Below is an example error when attempting to build with an incompatible Java version.

```
./gradlew trafficCaptureProxyServer:build

* What went wrong:
A problem occurred evaluating project ':trafficCaptureProxyServer'.
> Could not resolve all dependencies for configuration ':trafficCaptureProxyServer:opensearchSecurityPlugin'.
   > Failed to calculate the value of task ':trafficCaptureProxyServer:compileJava' property 'javaCompiler'.
      > No matching toolchains found for requested specification: {languageVersion=10, vendor=any, implementation=vendor-specific}.
         > No locally installed toolchains match (see https://docs.gradle.org/8.0.2/userguide/toolchains.html#sec:auto_detection) and toolchain download repositories have not been configured (see https://docs.gradle.org/8.0.2/userguide/toolchains.html#sub:download_repositories).

```
