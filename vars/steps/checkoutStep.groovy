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
        git branch: config.branch ?: 'main',
                url: config.repo ?: 'https://github.com/opensearch-project/opensearch-migrations.git'
    }
}
