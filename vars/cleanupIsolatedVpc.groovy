/**
 * Clean up an isolated VPC and all resources created by createIsolatedVpc
 * and deployIsolatedAosClusters.
 *
 * Deletes resources in reverse dependency order. All operations are idempotent.
 *
 * Usage:
 *   cleanupIsolatedVpc(region: 'us-east-1', vpcId: 'vpc-xxx', stage: 'airgap-42')
 */
def call(Map config = [:]) {
    def region = config.region ?: error("cleanupIsolatedVpc: 'region' is required")
    def vpcId = config.vpcId
    def stage = config.stage ?: error("cleanupIsolatedVpc: 'stage' is required")

    if (!vpcId) {
        echo "CLEANUP: No VPC ID provided, skipping isolated VPC cleanup"
        return
    }

    echo "CLEANUP: Deleting isolated VPC ${vpcId} and associated resources for stage ${stage}"

    // 1. Delete NAT gateways (must fully delete before EIP release and IGW detach)
    sh """
        NATS=\$(aws ec2 describe-nat-gateways --filter Name=vpc-id,Values=${vpcId} \
          --query "NatGateways[?State!='deleted'].NatGatewayId" --output text --region ${region} 2>/dev/null || echo "")
        for nat in \$NATS; do
            echo "CLEANUP: Deleting NAT gateway \$nat"
            aws ec2 delete-nat-gateway --nat-gateway-id \$nat --region ${region} || true
        done
        for nat in \$NATS; do
            echo "CLEANUP: Waiting for NAT gateway \$nat to reach deleted state..."
            aws ec2 wait nat-gateway-deleted --nat-gateway-ids \$nat --region ${region} || true
        done
    """

    // 2. Release EIPs tagged with this stage
    sh """
        for alloc in \$(aws ec2 describe-addresses --filters Name=tag:ma-stage,Values=${stage} \
          --query 'Addresses[*].AllocationId' --output text --region ${region} 2>/dev/null); do
            echo "CLEANUP: Releasing EIP \$alloc"
            aws ec2 release-address --allocation-id \$alloc --region ${region} || true
        done
    """

    // 3. Detach and delete internet gateways
    sh """
        for igw in \$(aws ec2 describe-internet-gateways --filters Name=attachment.vpc-id,Values=${vpcId} \
          --query 'InternetGateways[*].InternetGatewayId' --output text --region ${region} 2>/dev/null); do
            echo "CLEANUP: Detaching and deleting IGW \$igw"
            aws ec2 detach-internet-gateway --internet-gateway-id \$igw --vpc-id ${vpcId} --region ${region} || true
            aws ec2 delete-internet-gateway --internet-gateway-id \$igw --region ${region} || true
        done
    """

    // 4. Delete custom route tables (skip the main route table)
    sh """
        main_rt=\$(aws ec2 describe-route-tables --filters Name=vpc-id,Values=${vpcId} Name=association.main,Values=true \
          --query 'RouteTables[0].RouteTableId' --output text --region ${region} 2>/dev/null || echo "")
        for rt in \$(aws ec2 describe-route-tables --filters Name=vpc-id,Values=${vpcId} \
          --query 'RouteTables[*].RouteTableId' --output text --region ${region} 2>/dev/null); do
            [ "\$rt" = "\$main_rt" ] && continue
            for assoc in \$(aws ec2 describe-route-tables --route-table-ids \$rt \
              --query 'RouteTables[0].Associations[?!Main].RouteTableAssociationId' --output text --region ${region} 2>/dev/null); do
                aws ec2 disassociate-route-table --association-id \$assoc --region ${region} || true
            done
            echo "CLEANUP: Deleting route table \$rt"
            aws ec2 delete-route-table --route-table-id \$rt --region ${region} || true
        done
    """

    // 5. Delete all subnets
    sh """
        for subnet in \$(aws ec2 describe-subnets --filters Name=vpc-id,Values=${vpcId} \
          --query 'Subnets[*].SubnetId' --output text --region ${region} 2>/dev/null); do
            echo "CLEANUP: Deleting subnet \$subnet"
            aws ec2 delete-subnet --subnet-id \$subnet --region ${region} || true
        done
    """

    // 6. Delete VPC endpoints
    sh """
        for ep in \$(aws ec2 describe-vpc-endpoints --filters Name=vpc-id,Values=${vpcId} \
          --query 'VpcEndpoints[*].VpcEndpointId' --output text --region ${region} 2>/dev/null); do
            echo "CLEANUP: Deleting VPC endpoint \$ep"
            aws ec2 delete-vpc-endpoints --vpc-endpoint-ids \$ep --region ${region} || true
        done
    """

    // 7. Delete security groups (skip default)
    sh """
        for sg in \$(aws ec2 describe-security-groups --filters Name=vpc-id,Values=${vpcId} \
          --query "SecurityGroups[?GroupName!='default'].GroupId" --output text --region ${region} 2>/dev/null); do
            echo "CLEANUP: Deleting security group \$sg"
            aws ec2 delete-security-group --group-id \$sg --region ${region} || true
        done
    """

    // 8. Delete VPC
    echo "CLEANUP: Deleting VPC ${vpcId}"
    sh "aws ec2 delete-vpc --vpc-id ${vpcId} --region ${region} || true"
}
