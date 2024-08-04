import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {
    MigrationSSMParameter,
    createMigrationStringParameter,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy,
    getMigrationStringParameterValue
} from "../common-utilities";
import { ClusterBasicAuth, ClusterSigV4Auth, ClusterYaml } from "../migration-services-yaml";
import { Stack } from "aws-cdk-lib";

interface ClusterDetails {
    endpoint: string;
    basic_auth?: {
        username: string;
        password_from_secret_arn?: string;
        password?: string;
    };
    sigv4?: {
        region?: string;
        service?: string;
    };
    no_auth?: string;
  }
  

export interface PrexistingClustersProps extends StackPropsExt {
    readonly targetClusterDetails?: ClusterDetails; // TODO: make a real type to contain these
    readonly sourceClusterDetails?: ClusterDetails;
}

export class PrexistingClustersStack extends Stack {
    sourceClusterYaml: ClusterYaml;
    targetClusterYaml: ClusterYaml;

    constructor(scope: Construct, id: string, props: PrexistingClustersProps) {
        super(scope, id, props)
        this.targetClusterYaml = new ClusterYaml()
        this.sourceClusterYaml = new ClusterYaml()

        if (props.targetClusterDetails) {
            this.targetClusterYaml.endpoint = props.targetClusterDetails.endpoint;
            const endpointSSM = createMigrationStringParameter(this, props.targetClusterDetails["endpoint"], {
                parameter: MigrationSSMParameter.OS_CLUSTER_ENDPOINT,
                defaultDeployId: props.defaultDeployId,
                stage: props.stage,
            });
            if (props.targetClusterDetails.basic_auth) {
                this.targetClusterYaml.basic_auth = new ClusterBasicAuth(props.targetClusterDetails.basic_auth);
                const secretSSM = createMigrationStringParameter(this,
                    `${props.targetClusterDetails.basic_auth.username} ${props.targetClusterDetails.basic_auth.password_from_secret_arn}`, {
                    parameter: MigrationSSMParameter.OS_USER_AND_SECRET_ARN,
                    defaultDeployId: props.defaultDeployId,
                stage: props.stage,
                });
            } else if (props.targetClusterDetails.no_auth) {
                this.targetClusterYaml.no_auth = props.targetClusterDetails.no_auth
            } else {
                // Assume sigv4 unless otherwise specified
                this.targetClusterYaml.sigv4 = new ClusterSigV4Auth();

                if (props.targetClusterDetails.sigv4?.region) {
                    this.targetClusterYaml.sigv4.region = props.targetClusterDetails.sigv4.region
                } else if (props.env?.region) {
                    this.targetClusterYaml.sigv4.region = props.env?.region
                }
                if (props.targetClusterDetails.sigv4?.service) {
                    this.targetClusterYaml.sigv4.service = props.targetClusterDetails.sigv4.service
                }
            }
        }
        if (props.sourceClusterDetails) {
            this.sourceClusterYaml.endpoint = props.sourceClusterDetails.endpoint;
            const endpointSSM = createMigrationStringParameter(this, props.sourceClusterDetails["endpoint"], {
                parameter: MigrationSSMParameter.SOURCE_CLUSTER_ENDPOINT,
                defaultDeployId: props.defaultDeployId,
                stage: props.stage,
            });
            if (props.sourceClusterDetails.basic_auth) {
                this.sourceClusterYaml.basic_auth = new ClusterBasicAuth(props.sourceClusterDetails.basic_auth);
            } else if (props.sourceClusterDetails.sigv4) {
                this.sourceClusterYaml.sigv4 = new ClusterSigV4Auth();

                if (props.sourceClusterDetails.sigv4.region) {
                    this.sourceClusterYaml.sigv4.region = props.sourceClusterDetails.sigv4.region
                } else if (props.env?.region) {
                    this.sourceClusterYaml.sigv4.region = props.env?.region
                }
                if (props.sourceClusterDetails.sigv4.service) {
                    this.sourceClusterYaml.sigv4.service = props.sourceClusterDetails.sigv4.service
                }
            } else (
                this.sourceClusterYaml.no_auth = ""
            )
        }
        
    }

}
