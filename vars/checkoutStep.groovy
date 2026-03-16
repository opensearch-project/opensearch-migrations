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
        } else {
            git branch: branch, url: repoUrl
        }
    }
}
