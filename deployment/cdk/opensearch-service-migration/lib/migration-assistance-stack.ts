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
        const MSKConsumerPolicyConnect = new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ["kafka-cluster:Connect",
                "kafka-cluster:AlterCluster",
                "kafka-cluster:DescribeCluster"],
            resources: [props.MSKARN]
        })

        // Create IAM policy to read/write from kafka topics on the cluster
        const policyRegex1 = /([^//]+$)/gi
        const policyRegex2 = /:(cluster)/gi
        const policyShortARN = props.MSKARN.replace(policyRegex1, "*");
        const topicARN = policyShortARN.replace(policyRegex2, ":topic");

        const MSKConsumerPolicyReadAndWrite = new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ["kafka-cluster:*Topic*",
                "kafka-cluster:WriteData",
                "kafka-cluster:ReadData"],
            resources: [topicARN]
        })

        // Create IAM policy to join Kafka consumer groups
        let groupARN = policyShortARN.replace(policyRegex2, ":group");
        const MSKConsumerPolicyGroup = new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ["kafka-cluster:AlterGroup",
                "kafka-cluster:DescribeGroup"],
            resources: [groupARN]
        })

        const MSKConsumerAccessDoc = new PolicyDocument({
            statements: [MSKConsumerPolicyConnect, MSKConsumerPolicyReadAndWrite, MSKConsumerPolicyGroup]
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
            directory: join(__dirname, "../../../../TrafficCapture"),
            file: join("kafkaPrinter/docker/Dockerfile")
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

        // Create Traffic Replayer Container
        const trafficReplayerImage = new DockerImageAsset(this, "TrafficReplayerImage", {
            directory: join(__dirname, "../../../../TrafficCapture"),
            file: join("trafficReplayer/docker/Dockerfile")
        });
        const trafficReplayerContainer = migrationFargateTask.addContainer("TrafficReplayerContainer", {
            image: ContainerImage.fromDockerImageAsset(trafficReplayerImage),
            // Add in region and stage
            containerName: "traffic-replayer",
            environment: {"TARGET_CLUSTER_ENDPOINT": "http://" + props.targetEndpoint + ":80"},
            logging: LogDrivers.awsLogs({ streamPrefix: 'traffic-replayer-container-lg', logRetention: 30 })
        });

        // Create Traffic Comparator Container
        const trafficComparatorImage = new DockerImageAsset(this, "TrafficComparatorImage", {
            directory: join(__dirname, "../../..", "docker/traffic-comparator")
            // For local traffic comparator usage, replace directory path with own fs path
            //directory: "../../../../../mikayla-forks/traffic-comparator",
            //file: "docker/Dockerfile-trafficcomparator"
        });
        const trafficComparatorContainer = migrationFargateTask.addContainer("TrafficComparatorContainer", {
            image: ContainerImage.fromDockerImageAsset(trafficComparatorImage),
            // Add in region and stage
            containerName: "traffic-comparator",
            environment: {},
            // portMappings: [{containerPort: 9220}],
            logging: LogDrivers.awsLogs({ streamPrefix: 'traffic-comparator-container-lg', logRetention: 30 })
        });

        // To create Jupyter notebook container from local traffic comparator, replace directory path with own fs path
        // const trafficComparatorJupyterImage = new DockerImageAsset(this, "TrafficComparatorJupyterImage", {
        //     directory: "../../../../../mikayla-forks/traffic-comparator",
        //     file: "docker/Dockerfile-trafficcomparator-jupyter"
        // });
        // const trafficComparatorJupyterContainer = migrationFargateTask.addContainer("TrafficComparatorJupyterContainer", {
        //     image: ContainerImage.fromDockerImageAsset(trafficComparatorJupyterImage),
        //     // Add in region and stage
        //     containerName: "traffic-comparator-jupyter",
        //     environment: {},
        //     portMappings: [{containerPort: 8888}],
        //     logging: LogDrivers.awsLogs({ streamPrefix: 'traffic-comparator-container-jupyter-lg', logRetention: 30 })
        // });
        //
        // trafficComparatorContainer.addMountPoints({
        //     containerPath: '/shared',
        //     sourceVolume: 'shared-traffic-comparator-volume',
        //     readOnly: false,
        // });
        // trafficComparatorJupyterContainer.addMountPoints({
        //     containerPath: '/shared',
        //     sourceVolume: 'shared-traffic-comparator-volume',
        //     readOnly: false,
        // });
        //
        // // Mount the shared volume to the container
        // migrationFargateTask.addVolume({
        //     name: 'shared-traffic-comparator-volume',
        // });

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