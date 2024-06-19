@Library('migrations-shared-lib@checkin-jenkinsfile')_

defaultIntegPipeline(
        gitUrl: 'https://github.com/lewijacn/opensearch-migrations.git',
        gitBranch: 'checkin-jenkinsfile',
        stage: 'test'
//        checkout: {
//            echo 'Custom Test Step'
//            git branch: "${env.GIT_BRANCH}", url: "${env.GIT_URL}"
//        }
)
