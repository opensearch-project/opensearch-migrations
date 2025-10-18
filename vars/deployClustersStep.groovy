#!/usr/bin/env groovy
import groovy.json.JsonOutput

def call(Map config = [:]) {
    def clusters = []

    if (config.sourceVer) {
        clusters << [
                clusterId               : "source",
                clusterVersion          : "${config.sourceVer}",
                clusterType             : "${config.sourceClusterType}",
                openAccessPolicyEnabled : true,
                domainRemovalPolicy     : "DESTROY"
        ]
    } else {
        echo "Source cluster not added because version was not provided"
    }

    if (config.targetVer) {
        clusters << [
                clusterId               : "target",
                clusterVersion          : "${config.targetVer}",
                clusterType             : "${config.targetClusterType}",
                openAccessPolicyEnabled : true,
                domainRemovalPolicy     : "DESTROY"
        ]
    } else {
        echo "Target cluster not added because version was not provided"
    }

    if (clusters.isEmpty()) {
        error("No clusters were defined. Provide at least source or target cluster.")
    }

    def clusterContextValues = [
            stage      : "${config.stage}",
            vpcAZCount : config.vpcAZCount ?: 2,
            clusters   : clusters
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