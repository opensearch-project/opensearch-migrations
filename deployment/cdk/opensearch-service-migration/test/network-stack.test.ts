import {NetworkStack} from "../lib/network-stack";
import {Template} from "aws-cdk-lib/assertions";
import {createStackComposer} from "./test-utils";
import {ContainerImage} from "aws-cdk-lib/aws-ecs";
import {StringParameter} from "aws-cdk-lib/aws-ssm";

// Mock value returned from SSM call
jest.spyOn(StringParameter, 'valueForStringParameter').mockImplementation(() => "vpc-123456");
// Mock using local Dockerfile (which may not exist and would fail synthesis) with the intent of using a "fake-image" from a public registry
jest.mock("aws-cdk-lib/aws-ecr-assets")
jest.spyOn(ContainerImage, 'fromDockerImageAsset').mockImplementation(() => ContainerImage.fromRegistry("fake-image"));

test('Test vpcEnabled setting that is disabled does not create stack', () => {
    const contextOptions = {
        vpcEnabled: false
    }

    const openSearchStacks = createStackComposer(contextOptions)

    openSearchStacks.stacks.forEach(function(stack) {
        expect(!(stack instanceof NetworkStack))
    })

})

test('Test vpcEnabled setting that is enabled without existing resources creates default VPC resources', () => {
    const contextOptions = {
        vpcEnabled: true,
        // This setting could be left out, but provides clarity into the subnets for this test case
        availabilityZoneCount: 2
    }

    const openSearchStacks = createStackComposer(contextOptions)

    const networkStack: NetworkStack = (openSearchStacks.stacks.filter((s) => s instanceof NetworkStack)[0]) as NetworkStack
    const networkTemplate = Template.fromStack(networkStack)

    networkTemplate.resourceCountIs("AWS::EC2::VPC", 1)
    networkTemplate.resourceCountIs("AWS::EC2::SecurityGroup", 1)
    // For each AZ, a private and public subnet is created
    networkTemplate.resourceCountIs("AWS::EC2::Subnet", 4)

    const vpc = networkStack.vpc
    expect(vpc.publicSubnets.length).toBe(2)
    expect(vpc.privateSubnets.length).toBe(2)
})

test('Test if addOnMigrationDeployId is provided, stack does not create VPC or Security Group', () => {
    const contextOptions = {
        addOnMigrationDeployId: "junit-addon",
        vpcEnabled: true,
        availabilityZoneCount: 2
    }

    const stacks = createStackComposer(contextOptions)

    const networkStack: NetworkStack = (stacks.stacks.filter((s) => s instanceof NetworkStack)[0]) as NetworkStack
    const networkTemplate = Template.fromStack(networkStack)

    networkTemplate.resourceCountIs("AWS::EC2::VPC", 0)
    networkTemplate.resourceCountIs("AWS::EC2::SecurityGroup", 0)
})