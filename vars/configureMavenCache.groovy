// Jenkins owns this shared cache; local and GitHub builds use the Gradle defaults.
def call() {
    if (env.MAVEN_REPOSITORY_URL?.trim() || env.MIGRATIONS_MAVEN_CACHE_ENABLED == 'false') {
        return
    }
    withEnv([
        "MIGRATIONS_CACHE_DOMAIN=${env.MIGRATIONS_MAVEN_CACHE_DOMAIN ?: 'opensearch-migrations'}",
        "MIGRATIONS_CACHE_REGION=${env.MIGRATIONS_MAVEN_CACHE_REGION ?: 'us-east-1'}"
    ]) {
        env.MAVEN_REPOSITORY_URL = sh(
            returnStdout: true,
            script: '''#!/usr/bin/env bash
set -euo pipefail
aws codeartifact get-repository-endpoint \
    --domain "$MIGRATIONS_CACHE_DOMAIN" --repository build-dependencies --format maven \
    --region "$MIGRATIONS_CACHE_REGION" --query repositoryEndpoint --output text
''').trim()
        env.MAVEN_REPOSITORY_USERNAME = 'aws'
        // Capture stdout without shell tracing: never print the authorization token.
        env.MAVEN_REPOSITORY_PASSWORD = sh(
            returnStdout: true,
            script: '''#!/usr/bin/env bash
set -euo pipefail
aws codeartifact get-authorization-token \
    --domain "$MIGRATIONS_CACHE_DOMAIN" --region "$MIGRATIONS_CACHE_REGION" \
    --duration-seconds 43200 --query authorizationToken --output text
''').trim()
    }
    echo 'Configured shared Maven/Gradle dependency cache (read-only agent access)'
}
