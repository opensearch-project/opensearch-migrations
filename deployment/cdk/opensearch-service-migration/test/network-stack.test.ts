import {App} from "aws-cdk-lib";
import {StackComposer} from "../lib/stack-composer";
import {NetworkStack} from "../lib/network-stack";
import {Template} from "aws-cdk-lib/assertions";

test('Test vpcEnabled setting that is disabled does not create stack', () => {
    const app = new App({
        context: {
            vpcEnabled: false
        }
    })

    const openSearchStacks = new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}, stage: "unittest"
    })

    openSearchStacks.stacks.forEach(function(stack) {
        expect(!(stack instanceof NetworkStack))
    })

})

test('Test vpcEnabled setting that is enabled without existing resources creates default VPC resources', () => {
    const app = new App({
        context: {
            vpcEnabled: true,
            // This setting could be left out, but provides clarity into the subnets for this test case
            availabilityZoneCount: 2
        }
    })

    const openSearchStacks = new StackComposer(app, {
        env: {account: "test-account", region: "us-east-1"}, stage: "unittest"
    })

    const networkStack: NetworkStack = (openSearchStacks.stacks.filter((s) => s instanceof NetworkStack)[0]) as NetworkStack
    const networkTemplate = Template.fromStack(networkStack)

    networkTemplate.resourceCountIs("AWS::EC2::VPC", 1)
    networkTemplate.resourceCountIs("AWS::EC2::SecurityGroup", 1)
    // For each AZ, a private and public subnet is created
    networkTemplate.resourceCountIs("AWS::EC2::Subnet", 4)

    const securityGroups = networkStack.domainSecurityGroups
    expect(securityGroups.length).toBe(1)
    const subnets = networkStack.domainSubnets
    expect(subnets).toBe(undefined)
    const vpc = networkStack.vpc
    expect(vpc.publicSubnets.length).toBe(2)
    expect(vpc.privateSubnets.length).toBe(2)
})