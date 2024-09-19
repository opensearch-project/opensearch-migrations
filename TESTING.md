## Gradle Usage

[Gradle](https://gradle.org/) is used to build this repo, including its java artifacts as well as docker images.  Gradle handles dependencies between projects, compiling java code, running [JUnit](https://junit.org/junit5/) tests, and building docker images.  It can also deploy a demo/test environment via docker-compose for a rapid develop experience (see [dockerSolution](./TrafficCapture/dockerSolution/README.md)).

The Gradle application is packaged within the repository, so one can simply run [gradlew](./gradlew) from the root of the repository.  `./gradlew tasks` will show the tasks available at the top-level.  `.../gradlew tasks` run in any subproject directory will show specific tasks that can be run for that project.  Gradle can publish a scan to `scans.gradle.com` of its logs, performance, etc. at the end of its run, which can be used to diagnose a number of issues from test failures to build performance.

This `OpensearchMigrations` Gradle project is composed of many subprojects, defined by [settings.gradle](settings.gradle).  Those projects are configured similarly in the [build.gradle](./build.gradle) file.  Additional settings are defined in the [gradle.properties](./gradle.properties) file.

## Tests and Parallelization  

Gradle is configured to run most tasks from the projects in parallel, with a special exemption for tests marked to run them in total isolation of anything else that the gradle parent process is doing.  Tasks that can be run in parallel include building targets and running tests.  Notice that typical dependency rules apply.  The number of tasks running concurrently will be limited by the maxWorkerCount that gradle is passed or configures (which is typically the # of CPUs).

Each project within the project has the same base test configuration.  Targets include `test`, `slowTest`, `isolatedTest`, and `fullTest`, which are defined within the root `build.gradle` file.  Those targets are defined via the @Tag("NAME") attribute on each test (class or method).  A project's `test` tasks will be run as part of its `build` task.

A summary of each project's target composition is as follows.  Notice that `allTests` exists at the top-level project to depend upon ALL of the test tasks across all of the projects.

| Target | Composition | Purpose |
|---|---|---|
|`slowTest`| `@Tag("longTest")`| Tests that are too slow to provide value for every build run |
|`isolatedTest`| `@Tag("isolatedTest")`| Tests that may skew more toward integration tests and may take seconds to minutes to run.  While these tests may require more time, they shouldn't require exhaustive use of resources or be sensitive to other tasks running in parallel |
|`test`| all other tests NOT marked with the tags above ("longTest" or "isolatedTest")| Tests that require significant or exclusive use of resources or have sensitive performance bounds |
|`fullTest`| a task dependent upon the tasks above | Convenience Task |

The `isolatedTest` task (for each project) will run each of the tagged tests in serial and will run the isolatedTest task itself in serial from all of the other tasks within the project.  While the `isolatedTest` task isn't marked as dependent upon the other tests, it is marked to run _after_ other tests if gradle is set to run them.  That eliminates the requirement that test or slowTest run BEFORE the isolatedTest target when a developer is trying to only run the isolatedTest target.  Likewise, `slowTest` isn't dependent upon `test`, but those two targets may run in parallel since there aren't isolation requirements.  Parallelization for the test runners IS configured for `test` and `slowTest` targets so that those tests may complete quicker on hardware with more capacity.

## Traffic Capture Memory Leak Detections

TrafficCapture overrides the test and slowTest targets to enable netty leak detection only for slowTest.  The regular test targets for the TrafficCapture subprojects sets and environment variable to disable leak detection and the `slowTest` target for those subprojects leaves the `disableMemoryLeakTests` unset but alters the tag definition to include all tests but those tagged with isolatedTest.
