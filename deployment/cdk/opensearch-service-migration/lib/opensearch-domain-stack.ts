import {Construct} from "constructs";
import {
  EbsDeviceVolumeType,
  ISecurityGroup, IVpc,
  SecurityGroup,
  SubnetFilter,
  SubnetSelection
} from "aws-cdk-lib/aws-ec2";
import {Domain, EngineVersion, TLSSecurityPolicy, ZoneAwarenessConfig} from "aws-cdk-lib/aws-opensearchservice";
import {RemovalPolicy, SecretValue, Stack} from "aws-cdk-lib";
import {IKey, Key} from "aws-cdk-lib/aws-kms";
import {PolicyStatement} from "aws-cdk-lib/aws-iam";
import {ILogGroup, LogGroup} from "aws-cdk-lib/aws-logs";
import {ISecret, Secret} from "aws-cdk-lib/aws-secretsmanager";
import {StackPropsExt} from "./stack-composer";
import {StringParameter} from "aws-cdk-lib/aws-ssm";


export interface OpensearchDomainStackProps extends StackPropsExt {
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
  readonly vpcSubnetIds?: string[],
  readonly vpcSecurityGroupIds?: string[],
  readonly availabilityZoneCount?: number,
  readonly domainRemovalPolicy?: RemovalPolicy,
  readonly domainAccessSecurityGroupParameter?: string,
  readonly endpointParameterName?: string
}


export class OpenSearchDomainStack extends Stack {


  createSSMParameters(domain: Domain, endpointParameterName: string|undefined, adminUserName: string|undefined, adminUserSecret: ISecret|undefined, stage: string, deployId: string) {
    
    const endpointParameter = endpointParameterName ?? "osClusterEndpoint"
    new StringParameter(this, 'SSMParameterOpenSearchEndpoint', {
      description: 'OpenSearch migration parameter for OpenSearch endpoint',
      parameterName: `/migration/${stage}/${deployId}/${endpointParameter}`,
      stringValue: domain.domainEndpoint
    });
    
    if (domain.masterUserPassword && !adminUserSecret) {
      console.log(`An OpenSearch domain fine-grained access control user was configured without an existing Secrets Manager secret, will not create SSM Parameter: /migration/${stage}/${deployId}/osUserAndSecret`)
    }
    else if (domain.masterUserPassword && adminUserSecret) {
      new StringParameter(this, 'SSMParameterOpenSearchFGACUserAndSecretArn', {
        description: 'OpenSearch migration parameter for OpenSearch configured fine-grained access control user and associated Secrets Manager secret ARN ',
        parameterName: `/migration/${stage}/${deployId}/osUserAndSecretArn`,
        stringValue: `${adminUserName} ${adminUserSecret.secretArn}`
      });
    }
  }

  constructor(scope: Construct, id: string, props: OpensearchDomainStackProps) {
    super(scope, id, props);

    const deployId = props.addOnMigrationDeployId ? props.addOnMigrationDeployId : props.defaultDeployId
    // Retrieve existing account resources if defined
    const earKmsKey: IKey|undefined = props.encryptionAtRestKmsKeyARN && props.encryptionAtRestEnabled ?
        Key.fromKeyArn(this, "earKey", props.encryptionAtRestKmsKeyARN) : undefined

    let adminUserSecret: ISecret|undefined = props.fineGrainedManagerUserSecretManagerKeyARN ?
        Secret.fromSecretCompleteArn(this, "managerSecret", props.fineGrainedManagerUserSecretManagerKeyARN) : undefined

    const appLG: ILogGroup|undefined = props.appLogGroup && props.appLogEnabled ?
        LogGroup.fromLogGroupArn(this, "appLogGroup", props.appLogGroup) : undefined

    const domainAccessSecurityGroupParameter = props.domainAccessSecurityGroupParameter ?? "osAccessSecurityGroupId"
    const defaultOSClusterAccessGroup = SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG",
        StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/${domainAccessSecurityGroupParameter}`))

    // Map objects from props

    let adminUserName: string|undefined = props.fineGrainedManagerUserName
    // Enable demo mode setting
    if (props.enableDemoAdmin) {
      adminUserName = "admin"
      adminUserSecret = new Secret(this, "demoUserSecret", {
        secretName: `demo-user-secret-${props.stage}-${deployId}`,
        // This is unsafe and strictly for ease of use in a demo mode setup
        secretStringValue: SecretValue.unsafePlainText("Admin123!")
      })
    }
    const zoneAwarenessConfig: ZoneAwarenessConfig|undefined = props.availabilityZoneCount ?
        {enabled: true, availabilityZoneCount: props.availabilityZoneCount} : undefined

    // If specified, these subnets will be selected to place the Domain nodes in. Otherwise, this is not provided
    // to the Domain as it has existing behavior to select private subnets from a given VPC
    let domainSubnets: SubnetSelection[]|undefined;
    if (props.vpc && props.vpcSubnetIds) {
      const selectSubnets = props.vpc.selectSubnets({
        subnetFilters: [SubnetFilter.byIds(props.vpcSubnetIds)]
      })
      domainSubnets = [selectSubnets]
    }

    // Retrieve existing SGs to apply to VPC Domain endpoints
    const securityGroups: ISecurityGroup[] = []
    securityGroups.push(defaultOSClusterAccessGroup)
    if (props.vpcSecurityGroupIds) {
      for (let i = 0; i < props.vpcSecurityGroupIds.length; i++) {
        securityGroups.push(SecurityGroup.fromLookupById(this, "domainSecurityGroup-" + i, props.vpcSecurityGroupIds[i]))
      }
    }

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
      vpcSubnets: domainSubnets,
      securityGroups: securityGroups,
      zoneAwareness: zoneAwarenessConfig,
      removalPolicy: props.domainRemovalPolicy
    });

    this.createSSMParameters(domain, props.endpointParameterName, adminUserName, adminUserSecret, props.stage, deployId)

  }
}
