import { App, Tags } from 'aws-cdk-lib';
import { readFileSync } from 'fs';
import { StackComposer } from "../lib/stack-composer";

export function createApp(): App {
  const app = new App();
  const versionFile = readFileSync('../../../VERSION', 'utf-8');
  const version = versionFile.replace(/\n/g, '');
  Tags.of(app).add("migration_deployment", version);

  const account = process.env.CDK_DEFAULT_ACCOUNT;
  const region = process.env.CDK_DEFAULT_REGION;
  const migrationsAppRegistryARN = process.env.MIGRATIONS_APP_REGISTRY_ARN;
  const customReplayerUserAgent = process.env.CUSTOM_REPLAYER_USER_AGENT;

  if (migrationsAppRegistryARN) {
    console.info(`App Registry mode is enabled for CFN stack tracking. Will attempt to import the App Registry application from the MIGRATIONS_APP_REGISTRY_ARN env variable of ${migrationsAppRegistryARN} and looking in the configured region of ${region}`);
  }

  new StackComposer(app, {
    migrationsAppRegistryARN: migrationsAppRegistryARN,
    customReplayerUserAgent: customReplayerUserAgent,
    migrationsSolutionVersion: version,
    env: { account: account, region: region }
  });

  return app;
}
