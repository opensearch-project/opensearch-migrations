def gitBranch = (params.GIT_BRANCH ?: 'jenkins-pipeline-eks-large-migration').replaceAll('[^\\x20-\\x7E]', '').trim()
def gitUrl = (params.GIT_REPO_URL ?: 'https://github.com/AndreKurait/opensearch-migrations.git').trim()

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

eksBYOSIntegPipeline()
