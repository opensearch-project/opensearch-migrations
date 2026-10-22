# Jenkins dependency cache

Jenkins agents share outbound network addresses. Their local Gradle caches help while an
agent exists, but replacement agents download the same packages again. Maven Central
can rate-limit that aggregate traffic, causing HTTP 429 failures in compilation and
Spotless's detached dependency configurations. See [issue #3413](https://github.com/opensearch-project/opensearch-migrations/issues/3413)
and [Sonatype's consumption-limit guidance](https://central.sonatype.org/faq/429-error/).

The shared CodeArtifact repository retains packages across agent replacements. It has
upstream repositories for Maven Central and the Gradle Plugin Portal. The Gradle
configuration uses it for project dependencies, `buildSrc`, and settings/build plugins.
The agent assumes a dedicated cache reader role that permits reading packages and
obtaining short-lived authentication. It does not grant package publishing or
repository administration.

## Provision

Deploy in the Jenkins agent account, using its existing EC2 agent role:

```bash
aws cloudformation deploy \
  --region us-east-1 \
  --stack-name opensearch-migrations-dependency-cache \
  --template-file jenkins/dependency-cache.yaml \
  --parameter-overrides AgentRoleName=YOUR_JENKINS_AGENT_ROLE \
  --capabilities CAPABILITY_NAMED_IAM
```

The Jenkins build stages call `configureMavenCache()` before invoking Gradle. It selects
domain `opensearch-migrations`, repository `build-dependencies`, and region `us-east-1`.
For a different installation, set `MIGRATIONS_MAVEN_CACHE_DOMAIN` and
`MIGRATIONS_MAVEN_CACHE_REGION` on the Jenkins job or controller, and provision the stack
with the matching `DomainName`. The agent role and repository must be in the same account.

The helper assumes the stack's `${DomainName}-maven-reader` role and writes a read-only
token with a 12-hour lifetime to a mode-600 file in a private directory under the
agent's Jenkins temporary directory. Only the endpoint and file path return to the
controller: tokens must never be assigned to Jenkins global `env`, which is exposed
by its build API. Each pipeline deletes its generated credential file in the final
`post` cleanup condition, after other cleanup steps that may still invoke Gradle.
Caller-provided credential files are not deleted.

Cache setup failure fails the build; it does not silently return to direct downloads.
Deployment-only stages do not require cache setup.

Jobs running older release tags need the repository configuration in those tags before
they can consume this cache.

## Use another repository manager

Local builds and GitHub Actions retain their public repository defaults. To use a
repository manager, set:

```bash
export MAVEN_REPOSITORY_URL=https://repository.example.org/maven-public/
# Set a username and one password source if basic authentication is required:
export MAVEN_REPOSITORY_USERNAME=cache-reader
export MAVEN_REPOSITORY_PASSWORD_FILE=/private/path/to/read-only-token
./gradlew :searchClusterTestFixtures:spotlessJava
```

The password file contains a UTF-8 token, with surrounding whitespace removed.
`MAVEN_REPOSITORY_PASSWORD` is also supported for local builds; do not set both
password sources. Prefer the file option in CI to keep tokens out of build metadata.

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
Maven server. They cover plugin markers, `buildSrc`, ordinary and detached dependencies
with environment or file credentials, public defaults, invalid credentials, and a
repository returning HTTP 429. Agent-script tests check private file permissions,
assumed-role use, secret-free output, and cleanup after failed token acquisition.

To exercise the original task on an agent through the deployed cache, use the same
credential acquisition script as Jenkins, then run with a fresh Gradle user home:

```bash
set +x
export MIGRATIONS_CACHE_DOMAIN=opensearch-migrations
export MIGRATIONS_CACHE_REGION=us-east-1
export MIGRATIONS_CACHE_TMP_DIR="$(mktemp -d)"
configuration="$(bash jenkins/configureMavenCache.sh)"
export MAVEN_REPOSITORY_URL="$(printf '%s\n' "$configuration" | head -n 1)"
export MAVEN_REPOSITORY_PASSWORD_FILE="$(printf '%s\n' "$configuration" | tail -n 1)"
export MAVEN_REPOSITORY_USERNAME=aws
trap 'rm -f -- "$MAVEN_REPOSITORY_PASSWORD_FILE";
      rmdir -- "$(dirname "$MAVEN_REPOSITORY_PASSWORD_FILE")" "$MIGRATIONS_CACHE_TMP_DIR"' EXIT
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew \
  :searchClusterTestFixtures:spotlessJava :dashboardsSanitizer:compileJava \
  --no-build-cache --rerun-tasks --no-daemon
```

The wrapper/JDK distribution downloads are separate from Maven dependency resolution.
The CodeArtifact package inventory should show Google Java Format 1.28.0, semver4j
6.0.0, and their transitive dependencies after this run. For Jenkins builds, also
verify the public build API contains no repository password and that the generated
credential file is gone after post-build cleanup.

## Roll back

Set `MIGRATIONS_MAVEN_CACHE_ENABLED=false` for subsequent Jenkins builds and unset any
explicit `MAVEN_REPOSITORY_*` override. Builds then use public repositories and remain
subject to their consumption limits. Let active builds finish before deleting the
CloudFormation stack; deleting it removes cached packages, the cache reader role,
and the additional agent role policy.
