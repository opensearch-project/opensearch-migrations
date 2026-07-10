/**
 * Create an isolated VPC with subnets that have no internet routes.
 *
 * Returns a map: [vpcId: 'vpc-xxx', subnetIds: 'subnet-aaa,subnet-bbb']
 *
 * Usage:
 *   def vpc = createIsolatedVpc(region: 'us-east-1', stage: 'airgap-42')
 *   echo "VPC: ${vpc.vpcId}, Subnets: ${vpc.subnetIds}"
 */
def call(Map config = [:]) {
    def region = config.region ?: error("createIsolatedVpc: 'region' is required")
    def stage = config.stage ?: error("createIsolatedVpc: 'stage' is required")
    def cidrBlock = config.cidrBlock ?: '10.213.0.0/16'

    def vpcId = sh(script: """
        aws ec2 create-vpc --cidr-block ${cidrBlock} --region ${region} \
          --tag-specifications 'ResourceType=vpc,Tags=[{Key=Name,Value=ma-isolated-${stage}},{Key=ma-stage,Value=${stage}}]' \
          --query 'Vpc.VpcId' --output text
    """, returnStdout: true).trim()

    sh "aws ec2 modify-vpc-attribute --vpc-id ${vpcId} --enable-dns-hostnames '{\"Value\":true}' --region ${region}"
    sh "aws ec2 modify-vpc-attribute --vpc-id ${vpcId} --enable-dns-support '{\"Value\":true}' --region ${region}"

    def subnetAz1 = sh(script: """
        aws ec2 create-subnet --vpc-id ${vpcId} --cidr-block 10.213.0.0/24 \
          --availability-zone ${region}a --region ${region} \
          --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=ma-isolated-${stage}-1},{Key=ma-stage,Value=${stage}}]' \
          --query 'Subnet.SubnetId' --output text
    """, returnStdout: true).trim()

    def subnetAz2 = sh(script: """
        aws ec2 create-subnet --vpc-id ${vpcId} --cidr-block 10.213.1.0/24 \
          --availability-zone ${region}b --region ${region} \
          --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=ma-isolated-${stage}-2},{Key=ma-stage,Value=${stage}}]' \
          --query 'Subnet.SubnetId' --output text
    """, returnStdout: true).trim()

    echo "Created isolated VPC ${vpcId} with subnets ${subnetAz1}, ${subnetAz2} (no internet routes)"
    return [vpcId: vpcId, subnetIds: "${subnetAz1},${subnetAz2}"]
}
