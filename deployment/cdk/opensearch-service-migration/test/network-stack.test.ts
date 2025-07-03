import { NetworkStack } from "../lib/network-stack";
import { Template } from "aws-cdk-lib/assertions";
import { createStackComposer } from "./test-utils";
import { ContainerImage } from "aws-cdk-lib/aws-ecs";
import { StringParameter } from "aws-cdk-lib/aws-ssm";
import { describe, beforeEach, afterEach, test, expect, jest } from '@jest/globals';
import { GatewayVpcEndpointAwsService, InterfaceVpcEndpointAwsService } from "aws-cdk-lib/aws-ec2";
import { Stack } from "aws-cdk-lib";


function getExpectedEndpoints(networkStack: NetworkStack): (InterfaceVpcEndpointAwsService | GatewayVpcEndpointAwsService)[] {
    return [
        InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS,
        InterfaceVpcEndpointAwsService.CLOUDWATCH_MONITORING,
        InterfaceVpcEndpointAwsService.ECR,
        InterfaceVpcEndpointAwsService.ECR_DOCKER,
        InterfaceVpcEndpointAwsService.ECS_AGENT,
        InterfaceVpcEndpointAwsService.ECS_TELEMETRY,
        InterfaceVpcEndpointAwsService.ECS,
        InterfaceVpcEndpointAwsService.ELASTIC_LOAD_BALANCING,
        InterfaceVpcEndpointAwsService.SECRETS_MANAGER,
        InterfaceVpcEndpointAwsService.SSM,
        InterfaceVpcEndpointAwsService.SSM_MESSAGES,
        InterfaceVpcEndpointAwsService.XRAY,
        GatewayVpcEndpointAwsService.S3,
        Stack.of(networkStack).region.startsWith('us-gov-')
            ? InterfaceVpcEndpointAwsService.ELASTIC_FILESYSTEM_FIPS
            : InterfaceVpcEndpointAwsService.ELASTIC_FILESYSTEM,
    ];
}

describe('NetworkStack Tests', () => {
    beforeEach(() => {
        // Mock value returned from SSM call
        jest.spyOn(StringParameter, 'valueForStringParameter').mockImplementation(() => "vpc-123456");
        jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("ServiceImage"));
    });

    afterEach(() => {
        jest.clearAllMocks();
        jest.resetModules();
        jest.restoreAllMocks();
    });

    test('Test vpcEnabled setting that is enabled without existing resources creates default VPC resources', () => {
        const contextOptions = {
            vpcEnabled: true,
            // This setting could be left out, but provides clarity into the subnets for this test case
            vpcAZCount: 2,
            sourceCluster: {
                "endpoint": "https://test-cluster",
                "auth": {"type": "none"},
                "version": "ES_7.10"
            }
        }

        const openSearchStacks = createStackComposer(contextOptions)

        const networkStack: NetworkStack = (openSearchStacks.stacks.filter((s) => s instanceof NetworkStack)[0]) as NetworkStack
        const networkTemplate = Template.fromStack(networkStack)

        networkTemplate.resourceCountIs("AWS::EC2::VPC", 1)
        networkTemplate.resourceCountIs("AWS::EC2::SecurityGroup", getExpectedEndpoints(networkStack).length)
        // For each AZ, a private and public subnet is created
        networkTemplate.resourceCountIs("AWS::EC2::Subnet", 4)

        const vpc = networkStack.vpcDetails.vpc
        expect(vpc.publicSubnets.length).toBe(2)
        expect(vpc.privateSubnets.length).toBe(2)
    });

    test('Test if addOnMigrationDeployId is provided, stack does not create VPC or Security Group', () => {
        const contextOptions = {
            addOnMigrationDeployId: "junit-addon",
            vpcEnabled: true,
            vpcAZCount: 2,
            sourceCluster: {
                "endpoint": "https://test-cluster",
                "auth": {"type": "none"},
                "version": "ES_7.10"
            }
        }

        const stacks = createStackComposer(contextOptions)

        const networkStack: NetworkStack = (stacks.stacks.filter((s) => s instanceof NetworkStack)[0]) as NetworkStack
        const networkTemplate = Template.fromStack(networkStack)

        networkTemplate.resourceCountIs("AWS::EC2::VPC", 0)
        networkTemplate.resourceCountIs("AWS::EC2::SecurityGroup", 0)
    });

    test('Test VPC Endpoints are created correctly', () => {
        const contextOptions = {
            vpcEnabled: true,
            vpcAZCount: 2,
            sourceCluster: {
                "endpoint": "https://test-cluster",
                "auth": {"type": "none"},
                "version": "ES_7.10"
            }
        }

        const openSearchStacks = createStackComposer(contextOptions)
        const networkStack: NetworkStack = (openSearchStacks.stacks.filter((s) => s instanceof NetworkStack)[0]) as NetworkStack
        const networkTemplate = Template.fromStack(networkStack)

        // Define the expected VPC endpoints
        const expectedEndpoints = getExpectedEndpoints(networkStack);

        // Check for S3 Gateway Endpoint
        networkTemplate.hasResourceProperties('AWS::EC2::VPCEndpoint', {
            VpcEndpointType: 'Gateway',
        });

        // Loop through the VPC endpoints in the network stack and check that the service name is unique and in the list of expectedEndpoints
        const vpcEndpoints = networkTemplate.findResources('AWS::EC2::VPCEndpoint');
        const uniqueServiceNames = new Set<string>();

        const expectedServiceNames = expectedEndpoints.map(endpoint => {
            return endpoint instanceof GatewayVpcEndpointAwsService ?
             endpoint.name.toLowerCase().split('.').pop() as string : endpoint.shortName.toLowerCase();
        });

        for (const endpointKey in vpcEndpoints) {
            const endpoint = vpcEndpoints[endpointKey];
            let serviceName: string;
            if (endpoint.Properties.ServiceName['Fn::Join']) {
                const joinParts = endpoint.Properties.ServiceName['Fn::Join'][1];
                serviceName = (joinParts[joinParts.length - 1] as string).split('.').slice(1).join('.') as string;
            } else {
                serviceName = endpoint.Properties.ServiceName.split('.').slice(3).join('.');
            }
            expect(uniqueServiceNames.has(serviceName)).toBe(false);
            uniqueServiceNames.add(serviceName);

            const matchingEndpoint = expectedServiceNames.find(e => serviceName.includes(e));
            if (!matchingEndpoint) {
                console.error(`Failed assertion for service: ${serviceName}`);
                console.error(`Expected: ${serviceName} to be in ${expectedServiceNames.join(', ')}`);
                console.error(`Received: ${matchingEndpoint}`);
            }
            expect(matchingEndpoint).toBeDefined();
        }

        expect(uniqueServiceNames.size).toBe(expectedEndpoints.length);
        // Verify the total number of VPC Endpoints
        networkTemplate.resourceCountIs('AWS::EC2::VPCEndpoint', expectedEndpoints.length);
    });
});
