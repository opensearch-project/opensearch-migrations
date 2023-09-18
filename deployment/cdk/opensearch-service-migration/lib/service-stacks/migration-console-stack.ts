import {Stack} from "aws-cdk-lib";
import {StackPropsExt} from "../stack-composer";
import {ISecurityGroup, IVpc} from "aws-cdk-lib/aws-ec2";
import {Cluster} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";


export interface MigrationConsoleProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly ecsCluster: Cluster,
    readonly serviceConnectSecurityGroup: ISecurityGroup
    readonly additionalServiceSecurityGroups?: ISecurityGroup[]
}

export class MigrationConsoleStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: MigrationConsoleProps) {
        super(scope, id, {
            serviceName: "migration-console",
            //vpc: props.vpc,
            //ecsCluster: props.ecsCluster,
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/migrationConsole"),
            securityGroups: [props.serviceConnectSecurityGroup],
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 1024,
            ...props
        });
        // const serviceCore = new MigrationServiceCore(scope, "serviceCore", {
        //     serviceName: "migration-console",
        //     dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/migrationConsole"),
        //     securityGroups: [props.serviceConnectSecurityGroup],
        //     taskCpuUnits: 512,
        //     taskMemoryLimitMiB: 1024,
        //     ...props
        // });
    }

}