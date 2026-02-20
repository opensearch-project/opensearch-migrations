import { Template } from 'aws-cdk-lib/assertions';
import { OpenSearchDomainStack } from "../lib/opensearch-domain-stack";
import { NetworkStack } from "../lib/network-stack";
import { createStackComposer } from "./test-utils";
import { ClusterYaml } from '../lib/migration-services-yaml';
import { ContainerImage } from "aws-cdk-lib/aws-ecs";
import { describe, beforeEach, afterEach, test, expect, jest} from '@jest/globals';

describe('OpenSearch Domain Stack Tests', () => {
  beforeEach(() => {
    jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("ServiceImage"));
  });

  afterEach(() => {
    jest.clearAllMocks();
    jest.resetModules();
    jest.restoreAllMocks();
  });

  test('Test primary context options are mapped with standard data type', () => {
    // The cdk.context.json and default-values.json files allow multiple data types
    const contextOptions = {
      engineVersion: "OS_2.3",
      domainName: "test-os-domain",
      dataNodeType: "r6.large.search",
      dataNodeCount: 5,
      dedicatedManagerNodeType: "r6g.large.search",
      dedicatedManagerNodeCount: 3,
      warmNodeType: "ultrawarm1.medium.search",
      warmNodeCount: 2,
      accessPolicies: {
        "Version": "2012-10-17",
        "Statement": [{
          "Effect": "Allow",
          "Principal": { "AWS": "arn:aws:iam::12345678912:user/test-user" },
          "Action": "es:ESHttp*",
          "Resource": "arn:aws:es:us-east-1:12345678912:domain/cdk-os-service-domain/*"
        }]
      },
      fineGrainedManagerUserARN: "arn:aws:iam::12345678912:user/test-user",
      enforceHTTPS: true,
      tlsSecurityPolicy: "TLS_1_2",
      ebsEnabled: true,
      ebsIops: 4000,
      ebsVolumeSize: 15,
      ebsVolumeType: "GP3",
      encryptionAtRestEnabled: true,
      encryptionAtRestKmsKeyARN: "arn:aws:kms:us-east-1:12345678912:key/abc123de-4888-4fa7-a508-3811e2d49fc3",
      loggingAppLogEnabled: true,
      loggingAppLogGroupARN: "arn:aws:logs:us-east-1:12345678912:log-group:test-log-group:*",
      nodeToNodeEncryptionEnabled: true,
      vpcEnabled: true,
      vpcId: "vpc-123456789abcdefgh",
      vpcSecurityGroupIds: ["sg-123456789abcdefgh", "sg-223456789abcdefgh"],
      vpcAZCount: 3,
      domainRemovalPolicy: "DESTROY",
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    const networkStack = openSearchStacks.stacks.filter((s) => s instanceof NetworkStack)[0]
    const networkTemplate = Template.fromStack(networkStack)
    assertPrimaryDomainStackTemplate(domainTemplate)
    // When existing resources are provided, the network stack should only create SSM parameters or osClusterAccessSG
    const resources = networkTemplate.toJSON().Resources;
    expect(resources).toBeDefined();
    for (const resourceKey in resources) {
      const resourceType = resources[resourceKey].Type;
      if (resourceType === 'AWS::EC2::SecurityGroup' || resourceType === 'AWS::EC2::SecurityGroupIngress') {
        expect(resourceKey).toMatch(/^osClusterAccessSG/);
      } else {
        expect(resourceType).toBe('AWS::SSM::Parameter');
      }
    }
  });

  test('Test primary context options are mapped with only string data type', () => {
    // CDK CLI commands pass all context values as strings
    const contextOptions = {
      engineVersion: "OS_2.3",
      domainName: "test-os-domain",
      dataNodeType: "r6.large.search",
      dataNodeCount: "5",
      dedicatedManagerNodeType: "r6g.large.search",
      dedicatedManagerNodeCount: "3",
      warmNodeType: "ultrawarm1.medium.search",
      warmNodeCount: "2",
      accessPolicies: "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::12345678912:user/test-user\"},\"Action\":\"es:ESHttp*\",\"Resource\":\"arn:aws:es:us-east-1:12345678912:domain/cdk-os-service-domain/*\"}]}",
      fineGrainedManagerUserARN: "arn:aws:iam::12345678912:user/test-user",
      enforceHTTPS: "true",
      tlsSecurityPolicy: "TLS_1_2",
      ebsEnabled: "true",
      ebsIops: "4000",
      ebsVolumeSize: "15",
      ebsVolumeType: "GP3",
      encryptionAtRestEnabled: "true",
      encryptionAtRestKmsKeyARN: "arn:aws:kms:us-east-1:12345678912:key/abc123de-4888-4fa7-a508-3811e2d49fc3",
      loggingAppLogEnabled: "true",
      loggingAppLogGroupARN: "arn:aws:logs:us-east-1:12345678912:log-group:test-log-group:*",
      nodeToNodeEncryptionEnabled: "true",
      vpcEnabled: "true",
      vpcId: "vpc-123456789abcdefgh",
      vpcSecurityGroupIds: "[\"sg-123456789abcdefgh\", \"sg-223456789abcdefgh\"]",
      vpcAZCount: "3",
      domainRemovalPolicy: "DESTROY",
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    const networkStack = openSearchStacks.stacks.filter((s) => s instanceof NetworkStack)[0]
    const networkTemplate = Template.fromStack(networkStack)
    assertPrimaryDomainStackTemplate(domainTemplate)
    // When existing resources are provided, the network stack should only create SSM parameters or osClusterAccessSG
    const resources = networkTemplate.toJSON().Resources;
    expect(resources).toBeDefined();
    for (const resourceKey in resources) {
      const resourceType = resources[resourceKey].Type;
      if (resourceType === 'AWS::EC2::SecurityGroup' || resourceType === 'AWS::EC2::SecurityGroupIngress') {
        expect(resourceKey).toMatch(/^osClusterAccessSG/);
      } else {
        expect(resourceType).toBe('AWS::SSM::Parameter');
      }
    }
  });

  test('Test alternate context options are mapped with standard data type', () => {
    // The cdk.context.json and default-values.json files allow multiple data types
    const contextOptions = {
      useUnsignedBasicAuth: true,
      fineGrainedManagerUserSecretARN: "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc",
      // Fine-grained access requires enforceHTTPS, encryptionAtRest, and nodeToNodeEncryption to be enabled
      enforceHTTPS: true,
      encryptionAtRestEnabled: true,
      nodeToNodeEncryptionEnabled: true,
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    assertAlternateDomainStackTemplate(domainTemplate)
  })

  test('Test alternate context options are mapped with only string data type', () => {
    // CDK CLI commands pass all context values as strings
    const contextOptions = {
      useUnsignedBasicAuth: "true",
      fineGrainedManagerUserSecretARN: "arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc",
      // Fine-grained access requires enforceHTTPS, encryptionAtRest, and nodeToNodeEncryption to be enabled
      enforceHTTPS: "true",
      encryptionAtRestEnabled: "true",
      nodeToNodeEncryptionEnabled: "true",
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "ES_7.10"
      }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    assertAlternateDomainStackTemplate(domainTemplate)
  })

  test('Test openAccessPolicy setting creates access policy when enabled', () => {
    const contextOptions = {
      openAccessPolicyEnabled: true,
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "OS_2.19"
      }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    // Check that openAccessPolicy is created
    domainTemplate.resourceCountIs("Custom::OpenSearchAccessPolicy", 1)
  })

  test('Test openAccessPolicy setting does not create access policy when disabled', () => {
    const contextOptions = {
      openAccessPolicyEnabled: false,
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "OS_2.19"
      }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    // Check that openAccessPolicy is not created
    domainTemplate.resourceCountIs("Custom::OpenSearchAccessPolicy", 0)
  })

  test('Test openAccessPolicy setting is mapped with string data type', () => {
    const contextOptions = {
      openAccessPolicyEnabled: true,
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "OS_2.19"
      }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    // Check that openAccessPolicy is created
    domainTemplate.resourceCountIs("Custom::OpenSearchAccessPolicy", 1)
  })

  test('Test default stack is created when empty context options are provided for non-required options', () => {
    const contextOptions = {
      dataNodeType: "",
      dataNodeCount: "",
      dedicatedManagerNodeType: "",
      dedicatedManagerNodeCount: "",
      warmNodeType: "",
      warmNodeCount: "",
      accessPolicies: "",
      useUnsignedBasicAuth: "",
      fineGrainedManagerUserARN: "",
      enforceHTTPS: "",
      tlsSecurityPolicy: "",
      ebsEnabled: "",
      ebsIops: "",
      ebsVolumeSize: "",
      ebsVolumeType: "",
      encryptionAtRestEnabled: "",
      encryptionAtRestKmsKeyARN: "",
      loggingAppLogEnabled: "",
      loggingAppLogGroupARN: "",
      nodeToNodeEncryptionEnabled: "",
      vpcEnabled: "",
      vpcId: "",
      vpcSubnetIds: "",
      vpcSecurityGroupIds: "",
      vpcAZCount: "",
      openAccessPolicyEnabled: "",
      domainRemovalPolicy: "",
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "OS_2.19"
      }
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
  })

  test('Test targetClusterYaml is populated', () => {
    const contextOptions = {
      sourceCluster: {
        "endpoint": "https://test-cluster",
        "auth": {"type": "none"},
        "version": "OS_2.19"
      }
    }
    const openSearchStacks = createStackComposer(contextOptions)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0] as OpenSearchDomainStack
    const yaml = domainStack.targetClusterYaml
    expect(yaml).toBeDefined()
    expect(yaml).toBeInstanceOf(ClusterYaml)
    expect(yaml.endpoint).toBeDefined()
  })
})

/*
 * This function will make assertions on the primary config options, which contains the first set of options, all of
 * which should not interfere with resource properties of other settings in the set
 */
function assertPrimaryDomainStackTemplate(template: Template) {
  // Check that accessPolicies policy is created
  template.resourceCountIs("Custom::OpenSearchAccessPolicy", 1)
  template.resourceCountIs("AWS::OpenSearchService::Domain", 1)
  template.hasResourceProperties("AWS::OpenSearchService::Domain", {
    EngineVersion: "OpenSearch_2.3",
    DomainName: "test-os-domain",
    AdvancedSecurityOptions: {
      Enabled: true,
      MasterUserOptions: {
        MasterUserARN: "arn:aws:iam::12345678912:user/test-user"
      }
    },
    ClusterConfig: {
      DedicatedMasterCount: 3,
      DedicatedMasterEnabled: true,
      DedicatedMasterType: "r6g.large.search",
      InstanceCount: 5,
      InstanceType: "r6.large.search",
      WarmCount: 2,
      WarmType: "ultrawarm1.medium.search",
      ZoneAwarenessConfig: {
        AvailabilityZoneCount: 3
      },
      ZoneAwarenessEnabled: true
    },
    DomainEndpointOptions: {
      EnforceHTTPS: true,
      TLSSecurityPolicy: "Policy-Min-TLS-1-2-2019-07"
    },
    EBSOptions: {
      EBSEnabled: true,
      Iops: 4000,
      VolumeSize: 15,
      VolumeType: "gp3"
    },
    EncryptionAtRestOptions: {
      Enabled: true,
      KmsKeyId: "arn:aws:kms:us-east-1:12345678912:key/abc123de-4888-4fa7-a508-3811e2d49fc3"
    },
    LogPublishingOptions: {
      ES_APPLICATION_LOGS: {
        CloudWatchLogsLogGroupArn: "arn:aws:logs:us-east-1:12345678912:log-group:test-log-group:*",
        Enabled: true
      }
    },
    /*
    * Only checking that the VPCOptions object is added here as normally the provided vpcId will perform a lookup to
    * determine these options, but seems to be auto mocked here
    */
    VPCOptions: {},
    NodeToNodeEncryptionOptions: {
      Enabled: true
    }
  })
  // Check our removal policy has been added
  template.hasResource("AWS::OpenSearchService::Domain", {
    DeletionPolicy: "Delete",
    UpdateReplacePolicy: "Delete"
  })
}

/*
 * This function will make assertions on the alternate config options, which contains options that would have been
 * impacted by the primary set of config options, all options here should not interfere with resource properties of
 * other settings in this set
 */
function assertAlternateDomainStackTemplate(template: Template) {
    // Check that useUnsignedBasicAuth access policy is created
    template.resourceCountIs("Custom::OpenSearchAccessPolicy", 1)
    template.resourceCountIs("AWS::OpenSearchService::Domain", 1)
    template.hasResourceProperties("AWS::OpenSearchService::Domain", {
        AdvancedSecurityOptions: {
            Enabled: true,
            MasterUserOptions: {
                MasterUserName: "{{resolve:secretsmanager:arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc:SecretString:username::}}",
                MasterUserPassword: "{{resolve:secretsmanager:arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc:SecretString:password::}}"
            }
        }
    })
}
