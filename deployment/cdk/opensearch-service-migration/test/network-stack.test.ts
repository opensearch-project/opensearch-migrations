import {App} from "aws-cdk-lib";
import {StackComposer} from "../lib/stack-composer";
import {NetworkStack} from "../lib/network-stack";
import {Template} from "aws-cdk-lib/assertions";
import {createStackComposer} from "./test-utils";

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