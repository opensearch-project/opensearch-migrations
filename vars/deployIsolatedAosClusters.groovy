/**
 * Deploy AOS test clusters in private subnets within an isolated VPC.
 *
 * Creates public subnets + IGW + NAT + private subnets (required by AOS VPC mode),
 * deploys AOS domains via deployClustersStep, and adds security group rules
 * so EKS pods can reach the AOS endpoints.
 *
 * Sets env.clusterDetailsJson (from deployClustersStep).
 *
 * Usage:
 *   deployIsolatedAosClusters(
 *       region: 'us-east-1', vpcId: 'vpc-xxx', stage: 'airgap-42',
 *       eksClusterName: 'migration-eks-cluster-airgap-42-us-east-1',
 *       sourceVer: 'ES_7.10', targetVer: 'OS_2.19',
 *       clusterContextFilePath: 'tmp/cluster-context.json'
 *   )
 */
def call(Map config = [:]) {
    def region = config.region ?: error("deployIsolatedAosClusters: 'region' is required")
    def vpcId = config.vpcId ?: error("deployIsolatedAosClusters: 'vpcId' is required")
    def stage = config.stage ?: error("deployIsolatedAosClusters: 'stage' is required")
    def eksClusterName = config.eksClusterName ?: error("deployIsolatedAosClusters: 'eksClusterName' is required")
    def clusterContextFilePath = config.clusterContextFilePath ?: error("deployIsolatedAosClusters: 'clusterContextFilePath' is required")
    def sourceVer = config.sourceVer ?: 'ES_7.10'
    def targetVer = config.targetVer ?: 'OS_2.19'

    // Steps 1-3 need AWS credentials for EC2 API calls.
    // deployClustersStep (step 4) handles its own withMigrationsTestAccount internally,
    // so we must NOT nest it inside another withMigrationsTestAccount.
    def privSubnet1, privSubnet2

    withMigrationsTestAccount(region: region) { accountId ->
        // 1. Create public subnets + IGW
        def pubSubnet1 = sh(script: """
            aws ec2 create-subnet --vpc-id ${vpcId} --cidr-block 10.213.10.0/24 \
              --availability-zone ${region}a --region ${region} \
              --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=ma-isolated-${stage}-pub-1},{Key=ma-stage,Value=${stage}}]' \
              --query 'Subnet.SubnetId' --output text
        """, returnStdout: true).trim()

        def pubSubnet2 = sh(script: """
            aws ec2 create-subnet --vpc-id ${vpcId} --cidr-block 10.213.11.0/24 \
              --availability-zone ${region}b --region ${region} \
              --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=ma-isolated-${stage}-pub-2},{Key=ma-stage,Value=${stage}}]' \
              --query 'Subnet.SubnetId' --output text
        """, returnStdout: true).trim()

        def igwId = sh(script: """
            aws ec2 create-internet-gateway --region ${region} \
              --tag-specifications 'ResourceType=internet-gateway,Tags=[{Key=Name,Value=ma-isolated-${stage}-igw},{Key=ma-stage,Value=${stage}}]' \
              --query 'InternetGateway.InternetGatewayId' --output text
        """, returnStdout: true).trim()
        sh "aws ec2 attach-internet-gateway --internet-gateway-id ${igwId} --vpc-id ${vpcId} --region ${region}"

        def pubRtId = sh(script: """
            aws ec2 create-route-table --vpc-id ${vpcId} --region ${region} \
              --tag-specifications 'ResourceType=route-table,Tags=[{Key=Name,Value=ma-isolated-${stage}-pub-rt},{Key=ma-stage,Value=${stage}}]' \
              --query 'RouteTable.RouteTableId' --output text
        """, returnStdout: true).trim()
        sh "aws ec2 create-route --route-table-id ${pubRtId} --destination-cidr-block 0.0.0.0/0 --gateway-id ${igwId} --region ${region}"
        sh "aws ec2 associate-route-table --route-table-id ${pubRtId} --subnet-id ${pubSubnet1} --region ${region}"
        sh "aws ec2 associate-route-table --route-table-id ${pubRtId} --subnet-id ${pubSubnet2} --region ${region}"

        // 2. Create NAT gateway
        def eipAllocId = sh(script: """
            aws ec2 allocate-address --domain vpc --region ${region} \
              --tag-specifications 'ResourceType=elastic-ip,Tags=[{Key=Name,Value=ma-isolated-${stage}-eip},{Key=ma-stage,Value=${stage}}]' \
              --query 'AllocationId' --output text
        """, returnStdout: true).trim()

        def natGwId = sh(script: """
            aws ec2 create-nat-gateway --subnet-id ${pubSubnet1} --allocation-id ${eipAllocId} --region ${region} \
              --tag-specifications 'ResourceType=natgateway,Tags=[{Key=Name,Value=ma-isolated-${stage}-nat},{Key=ma-stage,Value=${stage}}]' \
              --query 'NatGateway.NatGatewayId' --output text
        """, returnStdout: true).trim()
        sh "aws ec2 wait nat-gateway-available --nat-gateway-ids ${natGwId} --region ${region}"

        // 3. Create private subnets with NAT route (CDK classifies these as "private")
        privSubnet1 = sh(script: """
            aws ec2 create-subnet --vpc-id ${vpcId} --cidr-block 10.213.20.0/24 \
              --availability-zone ${region}a --region ${region} \
              --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=ma-isolated-${stage}-priv-1},{Key=ma-stage,Value=${stage}}]' \
              --query 'Subnet.SubnetId' --output text
        """, returnStdout: true).trim()

        privSubnet2 = sh(script: """
            aws ec2 create-subnet --vpc-id ${vpcId} --cidr-block 10.213.21.0/24 \
              --availability-zone ${region}b --region ${region} \
              --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=ma-isolated-${stage}-priv-2},{Key=ma-stage,Value=${stage}}]' \
              --query 'Subnet.SubnetId' --output text
        """, returnStdout: true).trim()

        def privRtId = sh(script: """
            aws ec2 create-route-table --vpc-id ${vpcId} --region ${region} \
              --tag-specifications 'ResourceType=route-table,Tags=[{Key=Name,Value=ma-isolated-${stage}-priv-rt},{Key=ma-stage,Value=${stage}}]' \
              --query 'RouteTable.RouteTableId' --output text
        """, returnStdout: true).trim()
        sh "aws ec2 create-route --route-table-id ${privRtId} --destination-cidr-block 0.0.0.0/0 --nat-gateway-id ${natGwId} --region ${region}"
        sh "aws ec2 associate-route-table --route-table-id ${privRtId} --subnet-id ${privSubnet1} --region ${region}"
        sh "aws ec2 associate-route-table --route-table-id ${privRtId} --subnet-id ${privSubnet2} --region ${region}"

        echo "Created AOS networking: public subnets [${pubSubnet1}, ${pubSubnet2}], NAT ${natGwId}, private subnets [${privSubnet1}, ${privSubnet2}]"
    }

    // 4. Deploy AOS domains via deployClustersStep (handles its own withMigrationsTestAccount)
    dir('test') {
        deployClustersStep(
            stage: stage,
            clusterContextFilePath: clusterContextFilePath,
            sourceVer: sourceVer,
            sourceClusterType: 'OPENSEARCH_MANAGED_SERVICE',
            targetVer: targetVer,
            targetClusterType: 'OPENSEARCH_MANAGED_SERVICE',
            vpcId: vpcId,
            vpcSubnetIds: [privSubnet1, privSubnet2]
        )
    }

    // 5. Add security group rules: EKS → AOS on port 443
    // Domain names follow the pattern: <stage>-<clusterId> (clusterName overridden in deployClustersStep)
    withMigrationsTestAccount(region: region) { accountId ->
        def eksSg = sh(script: """
            aws eks describe-cluster --name ${eksClusterName} --region ${region} \
              --query 'cluster.resourcesVpcConfig.clusterSecurityGroupId' --output text
        """, returnStdout: true).trim()

        for (clusterId in ['source', 'target']) {
            def domainName = "${stage}-${clusterId}"
            def sg = sh(script: """
                aws opensearch describe-domain --domain-name ${domainName} --region ${region} \
                  --query 'DomainStatus.VPCOptions.SecurityGroupIds[0]' --output text 2>/dev/null || echo ''
            """, returnStdout: true).trim()
            if (sg) {
                echo "Adding SG rule: ${sg} (${domainName}) ← ${eksSg} (EKS) on port 443"
                sh "aws ec2 authorize-security-group-ingress --group-id ${sg} --protocol tcp --port 443 --source-group ${eksSg} --region ${region} || true"
            } else {
                echo "WARNING: Could not find security group for domain ${domainName}"
            }
        }
    }

    echo "AOS clusters deployed and security groups configured"
}
