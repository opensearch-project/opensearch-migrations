def gitBranch = (params.GIT_BRANCH ?: 'main').replaceAll('[^\\x20-\\x7E]', '').trim()
def gitUrl = (params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git').trim()

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

eksBYOSIntegPipeline()
