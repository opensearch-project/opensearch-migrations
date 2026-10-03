// Run after other post actions: Docker Compose cleanup can still invoke Gradle.
def call() {
    if (env.MIGRATIONS_MAVEN_CACHE_GENERATED_FILE) {
        sh '''#!/usr/bin/env bash
set -euo pipefail
rm -f -- "$MIGRATIONS_MAVEN_CACHE_GENERATED_FILE"
rmdir -- "$(dirname "$MIGRATIONS_MAVEN_CACHE_GENERATED_FILE")"
'''
        env.MIGRATIONS_MAVEN_CACHE_GENERATED_FILE = ''
        env.MAVEN_REPOSITORY_PASSWORD_FILE = ''
    }
}
