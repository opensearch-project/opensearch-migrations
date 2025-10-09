#!/usr/bin/env groovy
import groovy.json.JsonOutput

def call(Map config = [:]) {
    script {
        def clusterContextValues = [
                stage      : "${config.stage}",
                vpcAZCount : config.vpcAZCount ?: 2,
                clusters   : [
                        [
                                clusterId               : "source",
                                clusterVersion          : "${env.sourceVer}",
                                clusterType             : "${env.sourceClusterType}",
                                openAccessPolicyEnabled : true
                        ],
                        [
                                clusterId               : "target",
                                clusterVersion          : "${env.targetVer}",
                                clusterType             : "${env.targetClusterType}",
                                openAccessPolicyEnabled : true
                        ]
                ]
        ]

        def contextJsonStr = JsonOutput.prettyPrint(JsonOutput.toJson(clusterContextValues))
        writeFile(file: config.clusterContextFilePath, text: contextJsonStr)

        sh "echo 'Using cluster context file options:' && cat ${config.clusterContextFilePath}"

        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
            withAWS(role: 'JenkinsDeploymentRole',
                    roleAccount: MIGRATIONS_TEST_ACCOUNT_ID,
                    region: config.region ?: "us-east-1",
                    duration: 3600,
                    roleSessionName: 'jenkins-session') {
                sh "./awsDeployCluster.sh --stage ${config.stage} --context-file ${config.clusterContextFilePath}"
            }
        }

        def rawJsonFile = readFile "tmp/cluster-details-${config.stage}.json"
        echo "Cluster details JSON:\n${rawJsonFile}"
        env.clusterDetailsJson = rawJsonFile
    }
}