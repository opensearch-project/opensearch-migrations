import {StackPropsExt} from "../stack-composer";
import {VpcDetails} from "../network-stack";
import {SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol, Ulimit, UlimitName} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {MigrationServiceCore} from "./migration-service-core";
import {ISecret, Secret} from "aws-cdk-lib/aws-secretsmanager";
import {SecretValue} from "aws-cdk-lib";
import { MigrationSSMParameter, createMigrationStringParameter, getMigrationStringParameterValue } from "../common-utilities";

export interface OpenSearchContainerProps extends StackPropsExt {
    readonly vpcDetails: VpcDetails,
    readonly fargateCpuArch: CpuArchitecture,
    readonly fineGrainedManagerUserARN?: string,
    readonly fineGrainedManagerUserName?: string,
    readonly fineGrainedManagerUserSecretManagerKeyARN?: string,
    readonly enableDemoAdmin?: boolean,

}

const opensearch_target_initial_admin_password = "myStrongPassword123!";

const dnsNameForContainer = "opensearch";

/**
 * This is a testing stack which deploys a SINGLE node OpenSearch cluster as the target cluster for this solution with
 * no persistent storage. As such, it should NOT be used for production use cases.
 */
export class OpenSearchContainerStack extends MigrationServiceCore {

    private createSSMParameters(stage: string, deployId: string, adminUserName: string|undefined, adminUserSecret: ISecret|undefined) {
        if (adminUserSecret) {
            createMigrationStringParameter(this, `${adminUserName} ${adminUserSecret.secretArn}`, {
                parameter: MigrationSSMParameter.OS_USER_AND_SECRET_ARN,
                stage,
                defaultDeployId: deployId
            });
        }
    }

    constructor(scope: Construct, id: string, props: OpenSearchContainerProps) {
        super(scope, id, props)

        const deployId = props.addOnMigrationDeployId ? props.addOnMigrationDeployId : props.defaultDeployId

        const securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceSG", getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.SERVICE_SECURITY_GROUP_ID,
            })),
        ]

        let adminUserSecret: ISecret|undefined = props.fineGrainedManagerUserSecretManagerKeyARN ?
            Secret.fromSecretCompleteArn(this, "managerSecret", props.fineGrainedManagerUserSecretManagerKeyARN) : undefined
        let adminUserName: string|undefined = props.fineGrainedManagerUserName
        if (props.enableDemoAdmin) { // Enable demo mode setting
            adminUserName = "admin"
            adminUserSecret = new Secret(this, "demoUserSecret", {
                secretName: `demo-user-secret-${props.stage}-${deployId}`,
                // This is unsafe and strictly for ease of use in a demo mode setup
                secretStringValue: SecretValue.unsafePlainText(opensearch_target_initial_admin_password)
            })
        }

        const servicePort: PortMapping = {
            name: "opensearch-connect",
            hostPort: 9200,
            containerPort: 9200,
            protocol: Protocol.TCP
        }

        const ulimits: Ulimit[] = [
            {
                name: UlimitName.MEMLOCK,
                softLimit: -1,
                hardLimit: -1
            },
            {
                name: UlimitName.NOFILE,
                softLimit: 65536,
                hardLimit: 65536
            }
        ]

        this.createService({
            serviceName: dnsNameForContainer,
            dockerImageName: "opensearchproject/opensearch:2",
            securityGroups: securityGroups,
            environment: {
                "cluster.name": "os-docker-cluster",
                "node.name": "opensearch-node1",
                "discovery.seed_hosts": "opensearch-node1",
                "bootstrap.memory_lock": "true",
                "discovery.type": "single-node",
                "OPENSEARCH_INITIAL_ADMIN_PASSWORD": opensearch_target_initial_admin_password
            },
            portMappings: [servicePort],
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            cpuArchitecture: props.fargateCpuArch,
            ulimits: ulimits,
            ...props
        });

        if (props.enableDemoAdmin) {
            this.createSSMParameters(props.stage, deployId, adminUserName, adminUserSecret)
        }
    }
}
