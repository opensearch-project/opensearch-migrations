def call(Map config = [:]) {
    script {
        sh 'sudo chown -R $(whoami) .'
        sh 'sudo chmod -R u+w .'
        if (sh(script: 'git rev-parse --git-dir > /dev/null 2>&1', returnStatus: true) == 0) {
            echo 'Cleaning any existing git files in workspace'
            sh 'git reset --hard'
            sh 'git clean -fd'
        } else {
            echo 'No git project detected, this is likely an initial run of this pipeline on the worker'
        }
        def repoUrl = config.repo ?: 'https://github.com/opensearch-project/opensearch-migrations.git'
        def commit = config.commit?.trim()
        def branch = config.branch ?: 'main'
        if (commit) {
            // Jenkins git step cannot resolve raw commit SHAs as branch specs.
            // Use git CLI to fetch the branch and checkout the exact commit.
            sh "git init"
            sh "git remote set-url origin '${repoUrl}' || git remote add origin '${repoUrl}'"
            sh "git fetch origin '${branch}'"
            sh "git checkout '${commit}'"
        } else if (branch.startsWith('refs/tags/') || branch ==~ /^\d+\.\d+\.\d+$/) {
            // Tags cannot be resolved by the Jenkins git step (it only fetches refs/heads/*).
            // Use git CLI to fetch tags and checkout the tag directly.
            // Always fetch tags from the canonical repo since forks may not have them.
            def tag = branch.startsWith('refs/tags/') ? branch.replaceFirst('refs/tags/', '') : branch
            def canonicalRepo = 'https://github.com/opensearch-project/opensearch-migrations.git'
            sh "git init"
            sh "git remote set-url origin '${canonicalRepo}' || git remote add origin '${canonicalRepo}'"
            sh "git fetch origin --tags"
            sh "git checkout 'tags/${tag}'"
        } else {
            git branch: branch, url: repoUrl
        }
    }
}
