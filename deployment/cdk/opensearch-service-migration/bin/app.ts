#!/usr/bin/env node
import 'source-map-support/register';
import {readFileSync} from 'fs';
import {App, Tags} from 'aws-cdk-lib';
import {StackComposer} from "../lib/stack-composer";

const app = new App();
const version = readFileSync('../../../VERSION', 'utf-8')
Tags.of(app).add("migration_deployment", version)
const account = process.env.CDK_DEFAULT_ACCOUNT
const region = process.env.CDK_DEFAULT_REGION

new StackComposer(app, {
    env: { account: account, region: region }
});