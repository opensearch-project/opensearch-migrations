@Library('migrations-shared-lib@checkin-jenkinsfile')_

defaultIntegPipeline(
        checkout: {
            echo 'Custom Test Step'
            git branch: "${env.GIT_BRANCH}", url: "${env.GIT_URL}"
        }
)
