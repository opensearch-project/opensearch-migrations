#!/usr/bin/env node
import 'source-map-support/register';
import {App} from 'aws-cdk-lib';
import {StackComposer} from "../lib/stack-composer";

const app = new App();
const account = process.env.CDK_DEFAULT_ACCOUNT
const region = process.env.CDK_DEFAULT_REGION
const stage = process.env.CDK_DEPLOYMENT_STAGE
let copilotAppName = process.env.COPILOT_APP_NAME
let copilotEnvName = process.env.COPILOT_ENV_NAME
if (!stage) {
    throw new Error("Required environment variable CDK_DEPLOYMENT_STAGE has not been set (i.e. dev, gamma, PROD)")
}
if (!copilotAppName) {
    console.log("COPILOT_APP_NAME has not been set, defaulting to 'migration-copilot' for stack export identifier")
    copilotAppName = "migration-copilot"
}
if (!copilotEnvName) {
    console.log(`COPILOT_ENV_NAME has not been set, defaulting to CDK stage: ${stage} for stack export identifier`)
    copilotEnvName = stage
}

new StackComposer(app, {
    env: { account: account, region: region },
    stage: stage,
    copilotAppName: copilotAppName,
    copilotEnvName: copilotEnvName
});