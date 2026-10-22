import { App, Tags } from 'aws-cdk-lib';
import { readFileSync } from 'node:fs';
import { StackComposer } from "../lib/stack-composer";

export function createApp(): App {
  const app = new App();
  const versionFile = readFileSync('../../../VERSION', 'utf-8');
  const version = versionFile.replaceAll('\n', '');
  Tags.of(app).add("migration_deployment", version);

  const account = process.env.CDK_DEFAULT_ACCOUNT;
  const region = process.env.CDK_DEFAULT_REGION;

  // Temporarily allow both means for providing an additional migrations User Agent, but remove CUSTOM_REPLAYER_USER_AGENT
  // in future change
  let migrationsUserAgent = undefined
  if (process.env.CUSTOM_REPLAYER_USER_AGENT)
    migrationsUserAgent = process.env.CUSTOM_REPLAYER_USER_AGENT
  if (process.env.MIGRATIONS_USER_AGENT)
    migrationsUserAgent = process.env.MIGRATIONS_USER_AGENT

  new StackComposer(app, {
    migrationsUserAgent: migrationsUserAgent,
    migrationsSolutionVersion: version,
    env: { account: account, region: region }
  });

  return app;
}
