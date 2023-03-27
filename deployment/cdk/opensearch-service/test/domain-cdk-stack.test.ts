import {App} from 'aws-cdk-lib';
import {Template} from 'aws-cdk-lib/assertions';
import {StackComposer} from "../lib/stack-composer";
import * as testDefaultValues from "./default-values-test.json";
import {OpensearchServiceDomainCdkStack} from "../lib/opensearch-service-domain-cdk-stack";
import {NetworkStack} from "../lib/network-stack";

test('Test primary context options are mapped with standard data type', () => {
    // The cdk.context.json and default-values.json files allow multiple data types
    const app = new App({
        context: {
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
                    "Principal": {"AWS": "arn:aws:iam::123456789123:user/test-user"},
                    "Action": "es:ESHttp*",
                    "Resource": "arn:aws:es:us-east-1:123456789123:domain/cdk-os-service-domain/*"
                }]
            },
            fineGrainedManagerUserARN: "arn:aws:iam::123456789123:user/test-user",
            enforceHTTPS: true,
            tlsSecurityPolicy: "TLS_1_2",
            ebsEnabled: true,
            ebsIops: 4000,
            ebsVolumeSize: 15,
            ebsVolumeType: "GP3",
            encryptionAtRestEnabled: true,
            encryptionAtRestKmsKeyARN: "arn:aws:kms:us-east-1:123456789123:key/abc123de-4888-4fa7-a508-3811e2d49fc3",
            loggingAppLogEnabled: true,
            loggingAppLogGroupARN: "arn:aws:logs:us-east-1:123456789123:log-group:test-log-group:*",
            nodeToNodeEncryptionEnabled: true,
            vpcEnabled: true,
            vpcId: "vpc-123456789abcdefgh",
            vpcSubnetIds: ["subnet-123456789abcdefgh", "subnet-223456789abcdefgh"],
            vpcSecurityGroupIds: ["sg-123456789abcdefgh", "sg-223456789abcdefgh"],
            availabilityZoneCount: 3,
            domainRemovalPolicy: "DESTROY"
        }
    })

    const openSearchStacks = new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}
    })

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpensearchServiceDomainCdkStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    const networkStack = openSearchStacks.stacks.filter((s) => s instanceof NetworkStack)[0]
    const networkTemplate = Template.fromStack(networkStack)
    assertPrimaryDomainStackTemplate(domainTemplate)
    // When existing resources are provided the network stack creates no resources
    const resources = networkTemplate.toJSON().Resources;
    expect(resources === undefined)
})

test('Test primary context options are mapped with only string data type', () => {
    // CDK CLI commands pass all context values as strings
    const app = new App({
        context: {
            engineVersion: "OS_2.3",
            domainName: "test-os-domain",
            dataNodeType: "r6.large.search",
            dataNodeCount: "5",
            dedicatedManagerNodeType: "r6g.large.search",
            dedicatedManagerNodeCount: "3",
            warmNodeType: "ultrawarm1.medium.search",
            warmNodeCount: "2",
            accessPolicies: "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::123456789123:user/test-user\"},\"Action\":\"es:ESHttp*\",\"Resource\":\"arn:aws:es:us-east-1:123456789123:domain/cdk-os-service-domain/*\"}]}",
            fineGrainedManagerUserARN: "arn:aws:iam::123456789123:user/test-user",
            enforceHTTPS: "true",
            tlsSecurityPolicy: "TLS_1_2",
            ebsEnabled: "true",
            ebsIops: "4000",
            ebsVolumeSize: "15",
            ebsVolumeType: "GP3",
            encryptionAtRestEnabled: "true",
            encryptionAtRestKmsKeyARN: "arn:aws:kms:us-east-1:123456789123:key/abc123de-4888-4fa7-a508-3811e2d49fc3",
            loggingAppLogEnabled: "true",
            loggingAppLogGroupARN: "arn:aws:logs:us-east-1:123456789123:log-group:test-log-group:*",
            nodeToNodeEncryptionEnabled: "true",
            vpcEnabled: "true",
            vpcId: "vpc-123456789abcdefgh",
            vpcSubnetIds: "[\"subnet-123456789abcdefgh\", \"subnet-223456789abcdefgh\"]",
            vpcSecurityGroupIds: "[\"sg-123456789abcdefgh\", \"sg-223456789abcdefgh\"]",
            availabilityZoneCount: "3",
            domainRemovalPolicy: "DESTROY"
        }
    })

    const openSearchStacks = new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}
    })

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpensearchServiceDomainCdkStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    const networkStack = openSearchStacks.stacks.filter((s) => s instanceof NetworkStack)[0]
    const networkTemplate = Template.fromStack(networkStack)
    assertPrimaryDomainStackTemplate(domainTemplate)
    // When existing resources are provided the network stack creates no resources
    const resources = networkTemplate.toJSON().Resources;
    expect(resources === undefined)
})

test('Test alternate context options are mapped with standard data type', () => {
    // The cdk.context.json and default-values.json files allow multiple data types
    const app = new App({
        context: {
            useUnsignedBasicAuth: true,
            fineGrainedManagerUserName: "admin",
            fineGrainedManagerUserSecretManagerKeyARN: "arn:aws:secretsmanager:us-east-1:123456789123:secret:master-user-os-pass-123abc",
            // Fine-grained access requires enforceHTTPS, encryptionAtRest, and nodeToNodeEncryption to be enabled
            enforceHTTPS: true,
            encryptionAtRestEnabled: true,
            nodeToNodeEncryptionEnabled: true,
        }
    })

    const openSearchStacks = new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}
    })

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpensearchServiceDomainCdkStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    assertAlternateDomainStackTemplate(domainTemplate)
})

test('Test alternate context options are mapped with only string data type', () => {
    // CDK CLI commands pass all context values as strings
    const app = new App({
        context: {
            useUnsignedBasicAuth: "true",
            fineGrainedManagerUserName: "admin",
            fineGrainedManagerUserSecretManagerKeyARN: "arn:aws:secretsmanager:us-east-1:123456789123:secret:master-user-os-pass-123abc",
            // Fine-grained access requires enforceHTTPS, encryptionAtRest, and nodeToNodeEncryption to be enabled
            enforceHTTPS: "true",
            encryptionAtRestEnabled: "true",
            nodeToNodeEncryptionEnabled: "true",
        }
    })

    const openSearchStacks = new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}
    })

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpensearchServiceDomainCdkStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    assertAlternateDomainStackTemplate(domainTemplate)
})

test('Test openAccessPolicy setting creates access policy when enabled', () => {
    const app = new App({
        context: {
            openAccessPolicyEnabled: true
        }
    })

    const openSearchStacks = new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}
    })

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpensearchServiceDomainCdkStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    // Check that openAccessPolicy is created
    domainTemplate.resourceCountIs("Custom::OpenSearchAccessPolicy", 1)

})

test('Test openAccessPolicy setting does not create access policy when disabled', () => {
    const app = new App({
        context: {
            openAccessPolicyEnabled: false
        }
    })

    const openSearchStacks = new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}
    })

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpensearchServiceDomainCdkStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    // Check that openAccessPolicy is not created
    domainTemplate.resourceCountIs("Custom::OpenSearchAccessPolicy", 0)

})

test('Test openAccessPolicy setting is mapped with string data type', () => {
    const app = new App({
        context: {
            openAccessPolicyEnabled: "true"
        }
    })

    const openSearchStacks = new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}
    })

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpensearchServiceDomainCdkStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    // Check that openAccessPolicy is created
    domainTemplate.resourceCountIs("Custom::OpenSearchAccessPolicy", 1)

})

test( 'Test default stack is created with default values when no context options are provided', () => {
    const app = new App({
        context: {}
    })

    const openSearchStacks = new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}
    })

    const defaultValues: { [x: string]: (string); } = testDefaultValues
    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpensearchServiceDomainCdkStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
    domainTemplate.hasResourceProperties("AWS::OpenSearchService::Domain", {
        DomainName: defaultValues["domainName"],
    })
})

test( 'Test default stack is created when empty context options are provided for non-required options', () => {
    const app = new App({
        context: {
            dataNodeType: "",
            dataNodeCount: "",
            dedicatedManagerNodeType: "",
            dedicatedManagerNodeCount: "",
            warmNodeType: "",
            warmNodeCount: "",
            accessPolicies: "",
            useUnsignedBasicAuth: "",
            fineGrainedManagerUserARN: "",
            fineGrainedManagerUserName: "",
            fineGrainedManagerUserSecretManagerKeyARN: "",
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
            availabilityZoneCount: "",
            openAccessPolicyEnabled: "",
            domainRemovalPolicy: ""
        }
    })

    const openSearchStacks = new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}
    })

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpensearchServiceDomainCdkStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
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
                MasterUserARN: "arn:aws:iam::123456789123:user/test-user"
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
            KmsKeyId: "abc123de-4888-4fa7-a508-3811e2d49fc3"
        },
        LogPublishingOptions: {
            ES_APPLICATION_LOGS: {
                CloudWatchLogsLogGroupArn: "arn:aws:logs:us-east-1:123456789123:log-group:test-log-group:*",
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
                MasterUserName: "admin",
                MasterUserPassword: "{{resolve:secretsmanager:arn:aws:secretsmanager:us-east-1:123456789123:secret:master-user-os-pass-123abc:SecretString:::}}"
            }
        }
    })
}