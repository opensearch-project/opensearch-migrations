# Jenkins dependency cache

Jenkins agents share outbound network addresses. Their local Gradle caches help while an
agent exists, but replacement agents download the same packages again. Maven Central
can rate-limit that aggregate traffic, causing HTTP 429 failures in compilation and
Spotless's detached dependency configurations. See [issue #3413](https://github.com/opensearch-project/opensearch-migrations/issues/3413)
and [Sonatype's consumption-limit guidance](https://central.sonatype.org/faq/429-error/).

The shared CodeArtifact repository retains packages across agent replacements. It has
upstream repositories for Maven Central and the Gradle Plugin Portal. The Gradle
configuration uses it for project dependencies, `buildSrc`, and settings/build plugins.
The agent's policy permits reading packages and obtaining short-lived authentication.
It does not grant package publishing or repository administration.

## Provision

Deploy in the Jenkins agent account, using its existing EC2 agent role:

```bash
aws cloudformation deploy \
  --region us-east-1 \
  --stack-name opensearch-migrations-dependency-cache \
  --template-file jenkins/dependency-cache.yaml \
  --parameter-overrides AgentRoleName=YOUR_JENKINS_AGENT_ROLE \
  --capabilities CAPABILITY_IAM
```

The Jenkins build stages call `configureMavenCache()` before invoking Gradle. It selects
domain `opensearch-migrations`, repository `build-dependencies`, and region `us-east-1`.
For a different installation, set `MIGRATIONS_MAVEN_CACHE_DOMAIN` and
`MIGRATIONS_MAVEN_CACHE_REGION` on the Jenkins job or controller, and provision the stack
with the matching `DomainName`. The agent role and repository must be in the same account.

The helper captures a read-only token with a 12-hour lifetime without printing it or
writing it to the checkout. Cache setup failure fails the build; it does not silently
return to direct downloads. Cleanup and deployment-only stages do not require cache setup.

Jobs running older release tags need the repository configuration in those tags before
they can consume this cache.

## Use another repository manager

Local builds and GitHub Actions retain their public repository defaults. To use a
repository manager, set:

```bash
export MAVEN_REPOSITORY_URL=https://repository.example.org/maven-public/
# Set both variables only if the repository requires basic authentication:
export MAVEN_REPOSITORY_USERNAME=cache-reader
export MAVEN_REPOSITORY_PASSWORD=YOUR_READ_ONLY_TOKEN
./gradlew :searchClusterTestFixtures:spotlessJava
```

The endpoint must proxy **both Maven Central and the Gradle Plugin Portal**. A configured
endpoint replaces the public repositories; there is no public fallback for a cache
outage or a missing package. Use HTTPS, keep credentials out of the URL, and avoid shell
tracing when fetching or exporting tokens. Publishing repositories are configured
separately and are not redirected.

An existing `MAVEN_REPOSITORY_URL` also overrides Jenkins CodeArtifact setup.

## Validate

Run the repository-routing tests with Python 3 and JDK 21:

```bash
python3 -m unittest discover -s gradle/tests -v
```

These tests use real Gradle with empty dependency caches and a local authenticated
Maven server. They cover plugin markers, `buildSrc`, ordinary and detached dependencies,
public defaults, invalid credentials, and a repository returning HTTP 429.

To exercise the original task through the deployed cache, obtain an endpoint and token
using the agent's read-only role, then run with a fresh Gradle user home:

```bash
export MAVEN_REPOSITORY_URL="$(aws codeartifact get-repository-endpoint \
  --domain opensearch-migrations --repository build-dependencies --format maven \
  --region us-east-1 --query repositoryEndpoint --output text)"
export MAVEN_REPOSITORY_USERNAME=aws
export MAVEN_REPOSITORY_PASSWORD="$(aws codeartifact get-authorization-token \
  --domain opensearch-migrations --region us-east-1 \
  --query authorizationToken --output text)"
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew \
  :searchClusterTestFixtures:spotlessJava :dashboardsSanitizer:compileJava \
  --no-build-cache --rerun-tasks --no-daemon
unset MAVEN_REPOSITORY_PASSWORD
```

The wrapper/JDK distribution downloads are separate from Maven dependency resolution.
The CodeArtifact package inventory should show Google Java Format 1.28.0, semver4j
6.0.0, and their transitive dependencies after this run.

## Roll back

Set `MIGRATIONS_MAVEN_CACHE_ENABLED=false` for subsequent Jenkins builds and unset any
explicit `MAVEN_REPOSITORY_*` override. Builds then use public repositories and remain
subject to their consumption limits. Let active builds finish before deleting the
CloudFormation stack; deleting it removes cached packages and the additional agent
read policy.
