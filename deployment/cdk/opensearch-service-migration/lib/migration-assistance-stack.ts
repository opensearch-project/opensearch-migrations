import {Stack, StackProps} from "aws-cdk-lib";
import {
    Instance,
    InstanceClass,
    InstanceSize,
    InstanceType,
    IVpc,
    MachineImage, Peer, Port,
    SecurityGroup,
    SubnetType
} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {Cluster, ContainerImage, FargateService, FargateTaskDefinition, LogDrivers} from "aws-cdk-lib/aws-ecs";
import {DockerImageAsset} from "aws-cdk-lib/aws-ecr-assets";
import {join} from "path";
import {Effect, PolicyDocument, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";

export interface migrationStackProps extends StackProps {
    readonly vpc: IVpc,
    readonly MSKARN: string,
    readonly MSKBrokers: string[],
    readonly MSKTopic: string,
    readonly targetEndpoint: string
}


export class MigrationAssistanceStack extends Stack {

    constructor(scope: Construct, id: string, props: migrationStackProps) {
        super(scope, id, props);

        // Create IAM policy to connect to cluster
        const MSKConsumerPolicy1 = new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ["kafka-cluster:Connect",
                "kafka-cluster:AlterCluster",
                "kafka-cluster:DescribeCluster"],
            resources: [props.MSKARN]
        })

        // Create IAM policy to read/write from kafka topics on the cluster
        let policyRegex1 = /([^//]+$)/gi
        let policyRegex2 = /:(cluster)/gi
        let policyARN = props.MSKARN.replace(policyRegex1, "*");
        policyARN = policyARN.replace(policyRegex2, ":topic");

        const MSKConsumerPolicy2 = new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ["kafka-cluster:*Topic*",
                "kafka-cluster:WriteData",
                "kafka-cluster:ReadData"],
            resources: [policyARN]
        })


        const MSKConsumerAccessDoc = new PolicyDocument({
            statements: [MSKConsumerPolicy1, MSKConsumerPolicy2]
        })

        // Create IAM Role for Fargate Task to read from MSK Topic
        const MSKConsumerRole = new Role(this, 'MSKConsumerRole', {
            assumedBy: new ServicePrincipal('ecs-tasks.amazonaws.com'),
            description: 'Allow Fargate container to consume from MSK',
            inlinePolicies: {
                ReadMSKTopic: MSKConsumerAccessDoc,
            },
        });

        const ecsCluster = new Cluster(this, "ecsMigrationCluster", {
            vpc: props.vpc
        });

        const migrationFargateTask = new FargateTaskDefinition(this, "migrationFargateTask", {
            memoryLimitMiB: 2048,
            cpu: 512,
            taskRole: MSKConsumerRole
        });

        // Create MSK Consumer Container
        const MSKConsumerImage = new DockerImageAsset(this, "MSKConsumerImage", {
            directory: join(__dirname, "../../..", "docker/kafka-puller")
        });
        const MSKConsumerContainer = migrationFargateTask.addContainer("MSKConsumerContainer", {
            image: ContainerImage.fromDockerImageAsset(MSKConsumerImage),
            // Add in region and stage
            containerName: "msk-consumer",
            environment: {"KAFKA_BOOTSTRAP_SERVERS": props.MSKBrokers.toString(),
                "KAFKA_TOPIC_NAME": props.MSKTopic},
            // portMappings: [{containerPort: 9210}],
            logging: LogDrivers.awsLogs({ streamPrefix: 'msk-consumer-container-lg', logRetention: 30 })
        });

        // Create Traffic Comparator Container
        const trafficComparatorImage = new DockerImageAsset(this, "TrafficComparatorImage", {
            directory: join(__dirname, "../../..", "docker/traffic-comparator")
        });
        const trafficComparatorContainer = migrationFargateTask.addContainer("TrafficComparatorContainer", {
            image: ContainerImage.fromDockerImageAsset(trafficComparatorImage),
            // Add in region and stage
            containerName: "traffic-comparator",
            environment: {},
            // portMappings: [{containerPort: 9220}],
            logging: LogDrivers.awsLogs({ streamPrefix: 'traffic-comparator-container-lg', logRetention: 30 })
        });

        // Create Traffic Replayer Container
        const trafficReplayerImage = new DockerImageAsset(this, "TrafficReplayerImage", {
            directory: join(__dirname, "../../..", "docker/traffic-replayer")
        });
        const trafficReplayerContainer = migrationFargateTask.addContainer("TrafficReplayerContainer", {
            image: ContainerImage.fromDockerImageAsset(trafficReplayerImage),
            // Add in region and stage
            containerName: "traffic-replayer",
            environment: {"TARGET_CLUSTER_ENDPOINT": "https://" + props.targetEndpoint + ":80"},
            logging: LogDrivers.awsLogs({ streamPrefix: 'traffic-replayer-container-lg', logRetention: 30 })
        });

        // Create Fargate Service
        const migrationFargateService = new FargateService(this, "migrationFargateService", {
            cluster: ecsCluster,
            taskDefinition: migrationFargateTask,
            desiredCount: 1
        });

        // Creates a security group with open access via ssh
        const oinoSecurityGroup = new SecurityGroup(this, 'orchestratorSecurityGroup', {
            vpc: props.vpc,
            allowAllOutbound: true,
        });
        oinoSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(22));

        // Create EC2 instance for analysis of cluster in VPC
        const oino = new Instance(this, "orchestratorEC2Instance", {
            vpc: props.vpc,
            vpcSubnets: { subnetType: SubnetType.PUBLIC },
            instanceType: InstanceType.of(InstanceClass.T2, InstanceSize.MICRO),
            machineImage: MachineImage.latestAmazonLinux(),
            securityGroup: oinoSecurityGroup,
            // Manually created for now, to be automated soon
            keyName: "es-node-key"
        });
    }
}