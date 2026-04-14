/**
 * Shared wrapper for assuming the Jenkins deployment role in the migrations test account.
 *
 * Replaces the repeated pattern:
 *   withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
 *       withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: ..., duration: ..., roleSessionName: 'jenkins-session') {
 *           // body
 *       }
 *   }
 *
 * Usage:
 *   withMigrationsTestAccount(region: 'us-east-1') { accountId ->
 *       sh "aws sts get-caller-identity"
 *   }
 *
 *   withMigrationsTestAccount(region: 'us-east-1', duration: 7200) { accountId ->
 *       sh "echo ${accountId}"
 *   }
 */
def call(Map config = [:], Closure body) {
    def region = config.region
    def duration = config.duration ?: 3600

    if (!region) { error("withMigrationsTestAccount: 'region' is required") }

    withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
        withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: region, duration: duration, roleSessionName: 'jenkins-session') {
            body(MIGRATIONS_TEST_ACCOUNT_ID)
        }
    }
}
