// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import {Construct} from "constructs";
import {RemovalPolicy, Stack, StackProps} from "aws-cdk-lib";
import {OpensearchServiceDomainCdkStack} from "./opensearch-service-domain-cdk-stack";
import {EngineVersion, TLSSecurityPolicy} from "aws-cdk-lib/aws-opensearchservice";
import {EbsDeviceVolumeType} from "aws-cdk-lib/aws-ec2";
import {AnyPrincipal, Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import * as defaultValuesJson from "../default-values.json"
import {NetworkStack} from "./network-stack";

export class StackComposer {
    public stacks: Stack[] = [];

    constructor(scope: Construct, props: StackProps) {

        let networkStack: NetworkStack|undefined
        const stage = process.env.CDK_DEPLOYMENT_STAGE
        const account = props.env?.account
        const region = props.env?.region

        let version: EngineVersion
        let accessPolicies: PolicyStatement[]|undefined
        const defaultValues: { [x: string]: (string); } = defaultValuesJson
        const domainName = getContextForType('domainName', 'string')
        const dataNodeType = getContextForType('dataNodeType', 'string')
        const dataNodeCount = getContextForType('dataNodeCount', 'number')
        const dedicatedManagerNodeType = getContextForType('dedicatedManagerNodeType', 'string')
        const dedicatedManagerNodeCount = getContextForType('dedicatedManagerNodeCount', 'number')
        const warmNodeType = getContextForType('warmNodeType', 'string')
        const warmNodeCount = getContextForType('warmNodeCount', 'number')
        const useUnsignedBasicAuth = getContextForType('useUnsignedBasicAuth', 'boolean')
        const fineGrainedManagerUserARN = getContextForType('fineGrainedManagerUserARN', 'string')
        const fineGrainedManagerUserName = getContextForType('fineGrainedManagerUserName', 'string')
        const fineGrainedManagerUserSecretManagerKeyARN = getContextForType('fineGrainedManagerUserSecretManagerKeyARN', 'string')
        const enforceHTTPS = getContextForType('enforceHTTPS', 'boolean')
        const ebsEnabled = getContextForType('ebsEnabled', 'boolean')
        const ebsIops = getContextForType('ebsIops', 'number')
        const ebsVolumeSize = getContextForType('ebsVolumeSize', 'number')
        const encryptionAtRestEnabled = getContextForType('encryptionAtRestEnabled', 'boolean')
        const encryptionAtRestKmsKeyARN = getContextForType("encryptionAtRestKmsKeyARN", 'string')
        const loggingAppLogEnabled = getContextForType('loggingAppLogEnabled', 'boolean')
        const loggingAppLogGroupARN = getContextForType('loggingAppLogGroupARN', 'string')
        const noneToNodeEncryptionEnabled = getContextForType('nodeToNodeEncryptionEnabled', 'boolean')
        const vpcId = getContextForType('vpcId', 'string')
        const vpcEnabled = getContextForType('vpcEnabled', 'boolean')
        const vpcSecurityGroupIds = getContextForType('vpcSecurityGroupIds', 'object')
        const vpcSubnetIds = getContextForType('vpcSubnetIds', 'object')
        const openAccessPolicyEnabled = getContextForType('openAccessPolicyEnabled', 'boolean')
        const availabilityZoneCount = getContextForType('availabilityZoneCount', 'number')

        if (!domainName) {
            throw new Error("Domain name is not present and is a required field")
        }

        const engineVersion = getContextForType('engineVersion', 'string')
        if (engineVersion && engineVersion.startsWith("OS_")) {
            // Will accept a period delimited version string (i.e. 1.3) and return a proper EngineVersion
            version = EngineVersion.openSearch(engineVersion.substring(3))
        } else if (engineVersion && engineVersion.startsWith("ES_")) {
            version = EngineVersion.elasticsearch(engineVersion.substring(3))
        } else {
            throw new Error("Engine version is not present or does not match the expected format, i.e. OS_1.3 or ES_7.9")
        }

        if (openAccessPolicyEnabled) {
            const openPolicy = new PolicyStatement({
                effect: Effect.ALLOW,
                principals: [new AnyPrincipal()],
                actions: ["es:*"],
                resources: [`arn:aws:es:${region}:${account}:domain/${domainName}/*`]
            })
            accessPolicies = [openPolicy]
        } else {
            const accessPolicyJson = getContextForType('accessPolicies', 'object')
            accessPolicies = accessPolicyJson ? parseAccessPolicies(accessPolicyJson) : undefined
        }

        const tlsSecurityPolicyName = getContextForType('tlsSecurityPolicy', 'string')
        const tlsSecurityPolicy: TLSSecurityPolicy|undefined = tlsSecurityPolicyName ? TLSSecurityPolicy[tlsSecurityPolicyName as keyof typeof TLSSecurityPolicy] : undefined
        if (tlsSecurityPolicyName && !tlsSecurityPolicy) {
            throw new Error("Provided tlsSecurityPolicy does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_opensearchservice.TLSSecurityPolicy.html")
        }

        const ebsVolumeTypeName = getContextForType('ebsVolumeType', 'string')
        const ebsVolumeType: EbsDeviceVolumeType|undefined = ebsVolumeTypeName ? EbsDeviceVolumeType[ebsVolumeTypeName as keyof typeof EbsDeviceVolumeType] : undefined
        if (ebsVolumeTypeName && !ebsVolumeType) {
            throw new Error("Provided ebsVolumeType does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_ec2.EbsDeviceVolumeType.html")
        }

        const domainRemovalPolicyName = getContextForType('domainRemovalPolicy', 'string')
        const domainRemovalPolicy = domainRemovalPolicyName ? RemovalPolicy[domainRemovalPolicyName as keyof typeof RemovalPolicy] : undefined
        if (domainRemovalPolicyName && !domainRemovalPolicy) {
            throw new Error("Provided domainRemovalPolicy does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.RemovalPolicy.html")
        }

        // If enabled re-use existing VPC and/or associated resources or create new
        if (vpcEnabled) {
            networkStack = new NetworkStack(scope, 'networkStack', {
                vpcId: vpcId,
                vpcSubnetIds: vpcSubnetIds,
                vpcSecurityGroupIds: vpcSecurityGroupIds,
                availabilityZoneCount: availabilityZoneCount,
                stackName: `OSServiceNetworkCDKStack-${stage}-${region}`,
                description: "This stack contains resources to create/manage networking for an OpenSearch Service domain",
                ...props,
            })
            this.stacks.push(networkStack)
        }

        const opensearchStack = new OpensearchServiceDomainCdkStack(scope, 'opensearchDomainStack', {
            version: version,
            domainName: domainName,
            dataNodeInstanceType: dataNodeType,
            dataNodes: dataNodeCount,
            dedicatedManagerNodeType: dedicatedManagerNodeType,
            dedicatedManagerNodeCount: dedicatedManagerNodeCount,
            warmInstanceType: warmNodeType,
            warmNodes: warmNodeCount,
            accessPolicies: accessPolicies,
            useUnsignedBasicAuth: useUnsignedBasicAuth,
            fineGrainedManagerUserARN: fineGrainedManagerUserARN,
            fineGrainedManagerUserName: fineGrainedManagerUserName,
            fineGrainedManagerUserSecretManagerKeyARN: fineGrainedManagerUserSecretManagerKeyARN,
            enforceHTTPS: enforceHTTPS,
            tlsSecurityPolicy: tlsSecurityPolicy,
            ebsEnabled: ebsEnabled,
            ebsIops: ebsIops,
            ebsVolumeSize: ebsVolumeSize,
            ebsVolumeType: ebsVolumeType,
            encryptionAtRestEnabled: encryptionAtRestEnabled,
            encryptionAtRestKmsKeyARN: encryptionAtRestKmsKeyARN,
            appLogEnabled: loggingAppLogEnabled,
            appLogGroup: loggingAppLogGroupARN,
            nodeToNodeEncryptionEnabled: noneToNodeEncryptionEnabled,
            vpc: networkStack ? networkStack.vpc : undefined,
            vpcSubnets: networkStack ? networkStack.domainSubnets : undefined,
            vpcSecurityGroups: networkStack ? networkStack.domainSecurityGroups : undefined,
            availabilityZoneCount: availabilityZoneCount,
            domainRemovalPolicy: domainRemovalPolicy,
            stackName: `OSServiceDomainCDKStack-${stage}-${region}`,
            description: "This stack contains resources to create/manage an OpenSearch Service domain",
            ...props,
        });

        if (networkStack) {
            opensearchStack.addDependency(networkStack)
        }
        this.stacks.push(opensearchStack)

        function getContextForType(optionName: string, expectedType: string): any {
            const option = scope.node.tryGetContext(optionName)

            // If no context is provided and a default value exists, use it
            if (option === undefined && defaultValues[optionName]) {
                return defaultValues[optionName]
            }

            // Filter out invalid or missing options by setting undefined (empty strings, null, undefined, NaN)
            if (option !== false && option !== 0 && !option) {
                return undefined
            }
            // Values provided by the CLI will always be represented as a string and need to be parsed
            if (typeof option === 'string') {
                if (expectedType === 'number') {
                    return parseInt(option)
                }
                if (expectedType === 'boolean' || expectedType === 'object') {
                    return JSON.parse(option)
                }
            }
            // Values provided by the cdk.context.json should be of the desired type
            if (typeof option !== expectedType) {
                throw new Error(`Type provided by cdk.context.json for ${optionName} was ${typeof option} but expected ${expectedType}`)
            }
            return option
        }

        function parseAccessPolicies(jsonObject: { [x: string]: any; }): PolicyStatement[] {
            let accessPolicies: PolicyStatement[] = []
            const statements = jsonObject['Statement']
            if (!statements || statements.length < 1) {
                throw new Error ("Provided accessPolicies JSON must have the 'Statement' element present and not be empty, for reference https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements_statement.html")
            }
            // Access policies can provide a single Statement block or an array of Statement blocks
            if (Array.isArray(statements)) {
                for (let i = 0; i < statements.length; i++) {
                    const statement = PolicyStatement.fromJson(statements[i])
                    accessPolicies.push(statement)
                }
            }
            else {
                const statement = PolicyStatement.fromJson(statements)
                accessPolicies.push(statement)
            }
            return accessPolicies
        }
        
    }
}