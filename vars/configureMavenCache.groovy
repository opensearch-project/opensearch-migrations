// Jenkins owns this shared cache; local and GitHub builds use the Gradle defaults.
def call() {
    if (env.MAVEN_REPOSITORY_URL?.trim() || env.MIGRATIONS_MAVEN_CACHE_ENABLED == 'false') {
        return
    }
    withEnv([
        "MIGRATIONS_CACHE_DOMAIN=${env.MIGRATIONS_MAVEN_CACHE_DOMAIN ?: 'opensearch-migrations'}",
        "MIGRATIONS_CACHE_REGION=${env.MIGRATIONS_MAVEN_CACHE_REGION ?: 'us-east-1'}",
        "MIGRATIONS_CACHE_TMP_DIR=${pwd(tmp: true)}"
    ]) {
        // Global env is visible through Jenkins's build API. Return only public metadata;
        // the token and assumed-role credentials stay on the agent, outside the checkout.
        def configuration = sh(
            returnStdout: true,
            script: '''#!/usr/bin/env bash
set -euo pipefail
bash jenkins/configureMavenCache.sh
''').trim().split('\n')
        env.MAVEN_REPOSITORY_URL = configuration[0]
        env.MAVEN_REPOSITORY_USERNAME = 'aws'
        env.MAVEN_REPOSITORY_PASSWORD_FILE = configuration[1]
        env.MIGRATIONS_MAVEN_CACHE_GENERATED_FILE = configuration[1]
    }
    echo 'Configured shared Maven/Gradle dependency cache (read-only agent access)'
}
