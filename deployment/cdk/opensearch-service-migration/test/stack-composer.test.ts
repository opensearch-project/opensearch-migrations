import {App} from "aws-cdk-lib";
import {StackComposer} from "../lib/stack-composer";
import {Template} from "aws-cdk-lib/assertions";
import {OpenSearchDomainStack} from "../lib/open-search-domain-stack";
import {createStackComposer} from "./test-utils";

test('Test empty string provided for a parameter which has a default value, uses the default value', () => {

    const app = new App({
        context: {
            domainName: ""
        }
    })

    const openSearchStacks =  createStackComposer(app)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
})


test('Test invalid engine version format throws error', () => {

    const app = new App({
        context: {
            // Should be OS_1.3
            engineVersion: "OpenSearch_1.3"
        }
    })

    const createStackFunc = () => createStackComposer(app)

    expect(createStackFunc).toThrowError()
})

test('Test ES 7.10 engine version format is parsed', () => {

    const app = new App({
        context: {
            engineVersion: "ES_7.10"
        }
    })

    const openSearchStacks = createStackComposer(app)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
})

test('Test OS 1.3 engine version format is parsed', () => {

    const app = new App({
        context: {
            engineVersion: "OS_1.3"
        }
    })

    const openSearchStacks = createStackComposer(app)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    domainTemplate.resourceCountIs("AWS::OpenSearchService::Domain", 1)
})

test('Test access policy is parsed for proper array format', () => {

    const app = new App({
        context: {
            accessPolicies:
                {
                    "Version": "2012-10-17",
                    "Statement": [
                        {
                        "Effect": "Allow",
                        "Principal": {"AWS": "arn:aws:iam::12345678912:user/test-user"},
                        "Action": "es:ESHttp*",
                        "Resource": "arn:aws:es:us-east-1:12345678912:domain/test-os-domain/*"
                        },
                        {
                            "Effect": "Allow",
                            "Principal": {"AWS": "arn:aws:iam::12345678912:user/test-user2"},
                            "Action": "es:ESHttp*",
                            "Resource": "arn:aws:es:us-east-1:12345678912:domain/test-os-domain/*"
                        }]
                }
        }
    })

    const openSearchStacks = createStackComposer(app)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    // Check that accessPolicies policy is created
    domainTemplate.resourceCountIs("Custom::OpenSearchAccessPolicy", 1)
})

test('Test access policy is parsed for proper block format', () => {

    const app = new App({
        context: {
            accessPolicies:
                {
                    "Version": "2012-10-17",
                    "Statement": {
                        "Effect": "Allow",
                        "Principal": {"AWS": "*"},
                        "Action": "es:ESHttp*",
                        "Resource": "arn:aws:es:us-east-1:12345678912:domain/test-os-domain/*"
                    }
                }
        }
    })

    const openSearchStacks = createStackComposer(app)

    const domainStack = openSearchStacks.stacks.filter((s) => s instanceof OpenSearchDomainStack)[0]
    const domainTemplate = Template.fromStack(domainStack)
    // Check that accessPolicies policy is created
    domainTemplate.resourceCountIs("Custom::OpenSearchAccessPolicy", 1)
})

test('Test access policy missing Statement throws error', () => {

    const app = new App({
        context: {
            accessPolicies: {"Version":"2012-10-17"}
        }
    })

    const createStackFunc = () => createStackComposer(app)

    expect(createStackFunc).toThrowError()
})

test('Test access policy with empty Statement array throws error', () => {

    const app = new App({
        context: {
            accessPolicies: {"Version":"2012-10-17", "Statement":[]}
        }
    })

    const createStackFunc = () => createStackComposer(app)

    expect(createStackFunc).toThrowError()
})

test('Test access policy with empty Statement block throws error', () => {

    const app = new App({
        context: {
            accessPolicies: {"Version":"2012-10-17", "Statement":{}}
        }
    })

    const createStackFunc = () => createStackComposer(app)

    expect(createStackFunc).toThrowError()
})

test('Test access policy with improper Statement throws error', () => {

    const app = new App({
        context: {
            // Missing required fields in Statement
            accessPolicies: {"Version":"2012-10-17", "Statement":[{"Effect": "Allow"}]}
        }
    })

    const createStackFunc = () => createStackComposer(app)

    expect(createStackFunc).toThrowError()
})

test('Test invalid TLS security policy throws error', () => {

    const app = new App({
        context: {
            tlsSecurityPolicy: "TLS_0_9"
        }
    })

    const createStackFunc = () => createStackComposer(app)

    expect(createStackFunc).toThrowError()
})

test('Test invalid EBS volume type throws error', () => {

    const app = new App({
        context: {
            ebsVolumeType: "GP0",
        }
    })

    const createStackFunc = () => createStackComposer(app)

    expect(createStackFunc).toThrowError()
})

test('Test invalid domain removal policy type throws error', () => {

    const app = new App({
        context: {
            domainRemovalPolicy: "DELETE",
        }
    })

    const createStackFunc = () => createStackComposer(app)

    expect(createStackFunc).toThrowError()
})