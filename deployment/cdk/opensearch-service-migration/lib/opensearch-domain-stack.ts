import {Construct} from "constructs";
import {VpcDetails} from "./network-stack";
import {
  EbsDeviceVolumeType,
  ISecurityGroup,
  SecurityGroup,
  SubnetSelection
} from "aws-cdk-lib/aws-ec2";
import {Domain, EngineVersion, TLSSecurityPolicy, ZoneAwarenessConfig} from "aws-cdk-lib/aws-opensearchservice";
import {RemovalPolicy, SecretValue, Stack} from "aws-cdk-lib";
import {IKey, Key} from "aws-cdk-lib/aws-kms";
import {AnyPrincipal, Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {ILogGroup, LogGroup} from "aws-cdk-lib/aws-logs";
import {ISecret, Secret} from "aws-cdk-lib/aws-secretsmanager";
import {StackPropsExt} from "./stack-composer";
import { ClusterYaml } from "./migration-services-yaml";
import {
  ClusterAuth,
  ClusterBasicAuth,
  ClusterNoAuth,
  MigrationSSMParameter,
  createMigrationStringParameter,
  getMigrationStringParameterValue
} from "./common-utilities";
import { CdkLogger } from "./cdk-logger";


export interface OpensearchDomainStackProps extends StackPropsExt {
  readonly version: EngineVersion,
  readonly domainName: string,
  readonly dataNodeInstanceType?: string,
  readonly dataNodes?: number,
  readonly dedicatedManagerNodeType?: string,
  readonly dedicatedManagerNodeCount?: number,
  readonly warmInstanceType?: string,
  readonly warmNodes?: number
  readonly accessPolicyJson?: object,
  readonly openAccessPolicyEnabled?: boolean
  readonly useUnsignedBasicAuth?: boolean,
  readonly fineGrainedManagerUserARN?: string,
  readonly fineGrainedManagerUserName?: string,
  readonly fineGrainedManagerUserSecretManagerKeyARN?: string,
  readonly enableDemoAdmin?: boolean,
  readonly enforceHTTPS?: boolean,
  readonly tlsSecurityPolicy?: TLSSecurityPolicy,
  readonly ebsEnabled?: boolean,
  readonly ebsIops?: number,
  readonly ebsVolumeSize?: number,
  readonly ebsVolumeTypeName?: string,
  readonly encryptionAtRestEnabled?: boolean,
  readonly encryptionAtRestKmsKeyARN?: string,
  readonly appLogEnabled?: boolean,
  readonly appLogGroup?: string,
  readonly nodeToNodeEncryptionEnabled?: boolean,
  readonly vpcDetails?: VpcDetails,
  readonly vpcSecurityGroupIds?: string[],
  readonly domainRemovalPolicy?: RemovalPolicy,
  readonly domainAccessSecurityGroupParameter?: string

}


export class OpenSearchDomainStack extends Stack {
  targetClusterYaml: ClusterYaml;

  getEbsVolumeType(ebsVolumeTypeName: string) : EbsDeviceVolumeType|undefined {
    const ebsVolumeType: EbsDeviceVolumeType|undefined = ebsVolumeTypeName ? EbsDeviceVolumeType[ebsVolumeTypeName as keyof typeof EbsDeviceVolumeType] : undefined
    if (ebsVolumeTypeName && !ebsVolumeType) {
        throw new Error("Provided ebsVolumeType does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_ec2.EbsDeviceVolumeType.html")
    }
    return ebsVolumeType
  }

  createOpenAccessPolicy(domainName: string) {
    return new PolicyStatement({
        effect: Effect.ALLOW,
        principals: [new AnyPrincipal()],
        actions: ["es:*"],
        resources: [`arn:${this.partition}:es:${this.region}:${this.account}:domain/${domainName}/*`]
      })
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  parseAccessPolicies(jsonObject: Record<string, any>): PolicyStatement[] {
    const accessPolicies: PolicyStatement[] = []
    const statements = jsonObject['Statement']
    if (!statements || statements.length < 1) {
        throw new Error ("Provided accessPolicies JSON must have the 'Statement' element present and not be empty, for reference https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements_statement.html")
    }
    // Access policies can provide a single Statement block or an array of Statement blocks
    if (Array.isArray(statements)) {
        for (const statementBlock of statements) {
            const statement = PolicyStatement.fromJson(statementBlock)
            accessPolicies.push(statement)
        }
    }
    else {
        const statement = PolicyStatement.fromJson(statements)
        accessPolicies.push(statement)
    }
    return accessPolicies
  }

  createSSMParameters(domain: Domain, adminUserName: string|undefined, adminUserSecret: ISecret|undefined, stage: string, deployId: string) {
    createMigrationStringParameter(this, `https://${domain.domainEndpoint}:443`, {
      parameter: MigrationSSMParameter.OS_CLUSTER_ENDPOINT,
      defaultDeployId: deployId,
      stage,
    });
    if (domain.masterUserPassword && !adminUserSecret) {
      CdkLogger.info(`An OpenSearch domain fine-grained access control user was configured without an existing Secrets Manager secret, will not create SSM Parameter: /migration/${stage}/${deployId}/osUserAndSecret`)
    } else if (domain.masterUserPassword && adminUserSecret) {
      createMigrationStringParameter(this, `${adminUserName} ${adminUserSecret.secretArn}`, {
          parameter: MigrationSSMParameter.OS_USER_AND_SECRET_ARN,
          defaultDeployId: deployId,
          stage,
      });
    }
  }

  generateTargetClusterYaml(domain: Domain, adminUserName: string | undefined, adminUserSecret: ISecret|undefined, version: EngineVersion) {
    const clusterAuth = new ClusterAuth({});
    if (adminUserName) {
      clusterAuth.basicAuth = new ClusterBasicAuth({ username: adminUserName, password_from_secret_arn: adminUserSecret?.secretArn })
    } else {
      clusterAuth.noAuth = new ClusterNoAuth();
    }
     this.targetClusterYaml = new ClusterYaml({endpoint: `https://${domain.domainEndpoint}:443`, auth: clusterAuth, version: version.toString()})

  }

  constructor(scope: Construct, id: string, props: OpensearchDomainStackProps) {
    super(scope, id, props);

    const deployId = props.addOnMigrationDeployId ? props.addOnMigrationDeployId : props.defaultDeployId
    // Retrieve existing account resources if defined
    const earKmsKey: IKey|undefined = props.encryptionAtRestKmsKeyARN && props.encryptionAtRestEnabled ?
        Key.fromKeyArn(this, "earKey", props.encryptionAtRestKmsKeyARN) : undefined

    const appLG: ILogGroup|undefined = props.appLogGroup && props.appLogEnabled ?
        LogGroup.fromLogGroupArn(this, "appLogGroup", props.appLogGroup) : undefined

    const defaultOSClusterAccessGroup = SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", getMigrationStringParameterValue(this, {
        ...props,
        parameter: MigrationSSMParameter.OS_ACCESS_SECURITY_GROUP_ID,
    }));

    let adminUserSecret: ISecret|undefined = props.fineGrainedManagerUserSecretManagerKeyARN ?
        Secret.fromSecretCompleteArn(this, "managerSecret", props.fineGrainedManagerUserSecretManagerKeyARN) : undefined
    // Map objects from props
    let adminUserName: string|undefined = props.fineGrainedManagerUserName
    if (props.enableDemoAdmin) { // Enable demo mode setting
      adminUserName = "admin"
      adminUserSecret = new Secret(this, "demoUserSecret", {
        secretName: `demo-user-secret-${props.stage}-${deployId}`,
        // This is unsafe and strictly for ease of use in a demo mode setup
        secretStringValue: SecretValue.unsafePlainText("myStrongPassword123!")
      })
    }
    const zoneAwarenessConfig: ZoneAwarenessConfig|undefined = props.vpcDetails?.azCount && props.vpcDetails.azCount > 1 ?
        {enabled: true, availabilityZoneCount: props.vpcDetails.azCount} : undefined;

    // If specified, these subnets will be selected to place the Domain nodes in. Otherwise, this is not provided
    // to the Domain as it has existing behavior to select private subnets from a given VPC
    let domainSubnets: SubnetSelection[]|undefined;
    if (props.vpcDetails) {
      domainSubnets = [props.vpcDetails.subnetSelection]
    }

    // Retrieve existing SGs to apply to VPC Domain endpoints
    const securityGroups: ISecurityGroup[] = []
    securityGroups.push(defaultOSClusterAccessGroup)
    if (props.vpcSecurityGroupIds) {
      for (let i = 0; i < props.vpcSecurityGroupIds.length; i++) {
        securityGroups.push(SecurityGroup.fromLookupById(this, "domainSecurityGroup-" + i, props.vpcSecurityGroupIds[i]))
      }
    }

    const ebsVolumeType = props.ebsVolumeTypeName ? this.getEbsVolumeType(props.ebsVolumeTypeName) : undefined

    let accessPolicies: PolicyStatement[] | undefined
    if (props.openAccessPolicyEnabled) {
      accessPolicies = [this.createOpenAccessPolicy(props.domainName)]
    } else {
      accessPolicies = props.accessPolicyJson ? this.parseAccessPolicies(props.accessPolicyJson) : undefined
    }

    const domain = new Domain(this, 'Domain', {
      version: props.version,
      domainName: props.domainName,
      accessPolicies: accessPolicies,
      useUnsignedBasicAuth: props.useUnsignedBasicAuth,
      capacity: {
        dataNodeInstanceType: props.dataNodeInstanceType,
        dataNodes: props.dataNodes,
        masterNodeInstanceType: props.dedicatedManagerNodeType,
        masterNodes: props.dedicatedManagerNodeCount,
        warmInstanceType: props.warmInstanceType,
        warmNodes: props.warmNodes
      },
      fineGrainedAccessControl: {
        masterUserArn: props.fineGrainedManagerUserARN,
        masterUserName: adminUserName,
        masterUserPassword: adminUserSecret ? adminUserSecret.secretValue : undefined
      },
      nodeToNodeEncryption: props.nodeToNodeEncryptionEnabled,
      encryptionAtRest: {
        enabled: props.encryptionAtRestEnabled,
        kmsKey: earKmsKey
      },
      enforceHttps: props.enforceHTTPS,
      tlsSecurityPolicy: props.tlsSecurityPolicy,
      ebs: {
        enabled: props.ebsEnabled,
        iops: props.ebsIops,
        volumeSize: props.ebsVolumeSize,
        volumeType: ebsVolumeType
      },
      logging: {
        appLogEnabled: props.appLogEnabled,
        appLogGroup: appLG
      },
      vpc: props.vpcDetails?.vpc,
      vpcSubnets: domainSubnets,
      securityGroups: securityGroups,
      zoneAwareness: zoneAwarenessConfig,
      removalPolicy: props.domainRemovalPolicy
    });

    this.createSSMParameters(domain, adminUserName, adminUserSecret, props.stage, deployId)
    this.generateTargetClusterYaml(domain, adminUserName, adminUserSecret, props.version)
  }
}
