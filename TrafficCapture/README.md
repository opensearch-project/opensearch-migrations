## Compatibility

Must have Java version 11 installed.

The tools in this directory can only be built if you have Java version 11 installed.

The version is specified in `TrafficCapture/build.gradle` using a Java toolchain, which allows us
to decouple the Java version used by Gradle itself from Java version used by the tools here.

Any attempt to use a different version will cause the build to fail and will result in the following error (or similar)
depending on which tool/project is being built. The below example shows the error printed when running e.g `./gradlew 
trafficCaptureProxyServer:build`

```
* What went wrong:
A problem occurred evaluating project ':trafficCaptureProxyServer'.
> Could not resolve all dependencies for configuration ':trafficCaptureProxyServer:opensearchSecurityPlugin'.
   > Failed to calculate the value of task ':trafficCaptureProxyServer:compileJava' property 'javaCompiler'.
      > No matching toolchains found for requested specification: {languageVersion=10, vendor=any, implementation=vendor-specific}.
         > No locally installed toolchains match (see https://docs.gradle.org/8.0.2/userguide/toolchains.html#sec:auto_detection) and toolchain download repositories have not been configured (see https://docs.gradle.org/8.0.2/userguide/toolchains.html#sub:download_repositories).

```
