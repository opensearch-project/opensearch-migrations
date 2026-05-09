#!/usr/bin/env groovy
import groovy.json.JsonOutput

def call(Map config = [:]) {
    withMigrationsTestAccount(region: config.region ?: "us-east-1", duration: 7200) { accountId ->
        def clusters = []
        def usePublicAccess = config.publicAccess ?: false

        if (config.sourceVer) {
            def cluster = [
                    clusterId               : "source",
                    clusterName             : "${config.stage}-source",
                    clusterVersion          : "${config.sourceVer}",
                    clusterType             : "${config.sourceClusterType}",
                    domainRemovalPolicy     : "DESTROY",
                    publicAccess            : usePublicAccess
            ]
            if (usePublicAccess) {
                cluster.accessPolicies = sigv4AccessPolicy(accountId)
            } else {
                cluster.openAccessPolicyEnabled = true
            }
            clusters << cluster
        } else {
            echo "Source cluster not added because version was not provided"
        }

        if (config.targetVer) {
            def cluster = [
                    clusterId               : "target",
                    clusterName             : "${config.stage}-target",
                    clusterVersion          : "${config.targetVer}",
                    clusterType             : "${config.targetClusterType}",
                    domainRemovalPolicy     : "DESTROY",
                    publicAccess            : usePublicAccess
            ]
            if (usePublicAccess) {
                cluster.accessPolicies = sigv4AccessPolicy(accountId)
            } else {
                cluster.openAccessPolicyEnabled = true
            }
            clusters << cluster
        } else {
            echo "Target cluster not added because version was not provided"
        }

        if (clusters.isEmpty()) {
            error("No clusters were defined. Provide at least source or target cluster.")
        }

        def clusterContextValues = [
                stage      : "${config.stage}",
                clusters   : clusters
        ]

        if (config.vpcId) {
            clusterContextValues.vpcId = config.vpcId
            clusterContextValues.vpcSubnetIds = config.vpcSubnetIds
        } else {
            clusterContextValues.vpcAZCount = config.vpcAZCount ?: 2
        }

        def contextJsonStr = JsonOutput.prettyPrint(JsonOutput.toJson(clusterContextValues))
        writeFile(file: config.clusterContextFilePath, text: contextJsonStr)

        sh "echo 'Using cluster context file options:' && cat ${config.clusterContextFilePath}"
        sh "./awsDeployCluster.sh --stage ${config.stage} --context-file ${config.clusterContextFilePath}"
    }

    def rawJsonFile = readFile "tmp/cluster-details-${config.stage}.json"
    echo "Cluster details JSON:\n${rawJsonFile}"
    env.clusterDetailsJson = rawJsonFile
}

/** Account-scoped SigV4 access policy — allows all es:* actions from the deploying account */
private static def sigv4AccessPolicy(String accountId) {
    return [
        Version: "2012-10-17",
        Statement: [[
            Effect: "Allow",
            Principal: [AWS: "arn:aws:iam::${accountId}:root"],
            Action: "es:*",
            Resource: "*"
        ]]
    ]
}