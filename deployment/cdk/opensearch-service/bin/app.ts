#!/usr/bin/env node
import 'source-map-support/register';
import {App} from 'aws-cdk-lib';
import {StackComposer} from "../lib/stack-composer";

const app = new App();
const account = process.env.CDK_DEFAULT_ACCOUNT
const region = process.env.CDK_DEFAULT_REGION
new StackComposer(app, {
    env: { account: account, region: region },
});