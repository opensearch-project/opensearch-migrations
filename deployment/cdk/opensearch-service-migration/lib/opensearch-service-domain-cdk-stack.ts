import {Construct} from "constructs";
import {EbsDeviceVolumeType, ISecurityGroup, IVpc, SubnetSelection} from "aws-cdk-lib/aws-ec2";
import {Domain, EngineVersion, TLSSecurityPolicy, ZoneAwarenessConfig} from "aws-cdk-lib/aws-opensearchservice";
import {CfnOutput, RemovalPolicy, SecretValue, Stack} from "aws-cdk-lib";
import {IKey, Key} from "aws-cdk-lib/aws-kms";
import {PolicyStatement} from "aws-cdk-lib/aws-iam";
import {ILogGroup, LogGroup} from "aws-cdk-lib/aws-logs";
import {ISecret, Secret} from "aws-cdk-lib/aws-secretsmanager";
import {StackPropsExt} from "./stack-composer";


export interface opensearchServiceDomainCdkProps extends StackPropsExt {
  readonly version: EngineVersion,
  readonly domainName: string,
  readonly dataNodeInstanceType?: string,
  readonly dataNodes?: number,
  readonly dedicatedManagerNodeType?: string,
  readonly dedicatedManagerNodeCount?: number,
  readonly warmInstanceType?: string,
  readonly warmNodes?: number
  readonly accessPolicies?: PolicyStatement[],
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
  readonly ebsVolumeType?: EbsDeviceVolumeType,
  readonly encryptionAtRestEnabled?: boolean,
  readonly encryptionAtRestKmsKeyARN?: string,
  readonly appLogEnabled?: boolean,
  readonly appLogGroup?: string,
  readonly nodeToNodeEncryptionEnabled?: boolean,
  readonly vpc?: IVpc,
  readonly vpcSubnets?: SubnetSelection[],
  readonly vpcSecurityGroups?: ISecurityGroup[],
  readonly availabilityZoneCount?: number,
  readonly domainRemovalPolicy?: RemovalPolicy
}


export class OpensearchServiceDomainCdkStack extends Stack {
  public readonly domainEndpoint: string;

  constructor(scope: Construct, id: string, props: opensearchServiceDomainCdkProps) {
    super(scope, id, props);

    // The code that defines your stack goes here

    // Retrieve existing account resources if defined
    const earKmsKey: IKey|undefined = props.encryptionAtRestKmsKeyARN && props.encryptionAtRestEnabled ?
        Key.fromKeyArn(this, "earKey", props.encryptionAtRestKmsKeyARN) : undefined

    let adminUserSecret: ISecret|undefined = props.fineGrainedManagerUserSecretManagerKeyARN ?
        Secret.fromSecretCompleteArn(this, "managerSecret", props.fineGrainedManagerUserSecretManagerKeyARN) : undefined


    const appLG: ILogGroup|undefined = props.appLogGroup && props.appLogEnabled ?
        LogGroup.fromLogGroupArn(this, "appLogGroup", props.appLogGroup) : undefined

    // Map objects from props

    let adminUserName: string|undefined = props.fineGrainedManagerUserName
    // Enable demo mode setting
    if (props.enableDemoAdmin) {
      adminUserName = "admin"
      adminUserSecret = new Secret(this, "demoUserSecret", {
        secretName: "demo-user-secret",
        // This is unsafe and strictly for ease of use in a demo mode setup
        secretStringValue: SecretValue.unsafePlainText("Admin123!")
      })
    }
    const zoneAwarenessConfig: ZoneAwarenessConfig|undefined = props.availabilityZoneCount ?
        {enabled: true, availabilityZoneCount: props.availabilityZoneCount} : undefined

    const domain = new Domain(this, 'Domain', {
      version: props.version,
      domainName: props.domainName,
      accessPolicies: props.accessPolicies,
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
        volumeType: props.ebsVolumeType
      },
      logging: {
        appLogEnabled: props.appLogEnabled,
        appLogGroup: appLG
      },
      vpc: props.vpc,
      vpcSubnets: props.vpcSubnets,
      securityGroups: props.vpcSecurityGroups,
      zoneAwareness: zoneAwarenessConfig,
      removalPolicy: props.domainRemovalPolicy
    });

    this.domainEndpoint = domain.domainEndpoint

    const exports = [
      `export MIGRATION_DOMAIN_ENDPOINT=${this.domainEndpoint}`
    ]
    if (domain.masterUserPassword && !adminUserSecret) {
      console.log("A master user was configured without an existing Secrets Manager secret, will not export MIGRATION_DOMAIN_USER_NAME and MIGRATION_DOMAIN_USER_SECRET_ARN for Copilot")
    }
    else if (domain.masterUserPassword && adminUserSecret) {
      exports.push(`export MIGRATION_DOMAIN_USER_NAME=${adminUserName}`)
      exports.push(`export MIGRATION_DOMAIN_USER_SECRET_ARN=${adminUserSecret.secretArn}`)
    }
    new CfnOutput(this, 'CopilotDomainExports', {
      value: exports.join(";"),
      description: 'Exported Domain resource values created by CDK that are needed by Copilot container deployments',
    });
  }
}
