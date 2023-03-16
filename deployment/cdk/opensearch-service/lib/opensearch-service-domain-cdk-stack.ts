import { Construct } from 'constructs';
import {EbsDeviceVolumeType, IVpc, Vpc} from "aws-cdk-lib/aws-ec2";
import {Domain, EngineVersion, TLSSecurityPolicy} from "aws-cdk-lib/aws-opensearchservice";
import {RemovalPolicy, SecretValue, Stack, StackProps} from "aws-cdk-lib";
import {IKey, Key} from "aws-cdk-lib/aws-kms";
import {PolicyStatement} from "aws-cdk-lib/aws-iam";
import {ILogGroup, LogGroup} from "aws-cdk-lib/aws-logs";
import {Secret} from "aws-cdk-lib/aws-secretsmanager";


export interface opensearchServiceDomainCdkProps extends StackProps{
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
  readonly vpcId?: string,
  readonly domainRemovalPolicy?: RemovalPolicy
}


export class OpensearchServiceDomainCdkStack extends Stack {
  constructor(scope: Construct, id: string, props: opensearchServiceDomainCdkProps) {
    super(scope, id, props);

    // The code that defines your stack goes here

    // Retrieve existing account resources if defined
    const earKmsKey: IKey|undefined = props.encryptionAtRestKmsKeyARN && props.encryptionAtRestEnabled ?
        Key.fromKeyArn(this, "earKey", props.encryptionAtRestKmsKeyARN) : undefined

    const managerUserSecret: SecretValue|undefined = props.fineGrainedManagerUserSecretManagerKeyARN ?
        Secret.fromSecretCompleteArn(this, "managerSecret", props.fineGrainedManagerUserSecretManagerKeyARN).secretValue : undefined

    const appLG: ILogGroup|undefined = props.appLogGroup && props.appLogEnabled ?
        LogGroup.fromLogGroupArn(this, "appLogGroup", props.appLogGroup) : undefined

    const vpc: IVpc|undefined = props.vpcId ?
        Vpc.fromLookup(this, "domainVPC", {vpcId: props.vpcId}) : undefined


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
        masterUserName: props.fineGrainedManagerUserName,
        masterUserPassword: managerUserSecret
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
      vpc: vpc,
      removalPolicy: props.domainRemovalPolicy
    });
  }
}
