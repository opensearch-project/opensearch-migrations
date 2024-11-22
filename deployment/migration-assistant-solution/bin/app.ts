import 'source-map-support/register';
import { App, DefaultStackSynthesizer } from 'aws-cdk-lib';
import { SolutionsInfrastructureStack } from '../lib/solutions-stack';

const getProps = () => {
  const { CODE_BUCKET, SOLUTION_NAME, CODE_VERSION, STACK_NAME_SUFFIX } = process.env;
  if (typeof CODE_BUCKET !== 'string' || CODE_BUCKET.trim() === '') {
    console.warn(`Missing environment variable CODE_BUCKET, using a default value`);
  }

  if (typeof SOLUTION_NAME !== 'string' || SOLUTION_NAME.trim() === '') {
    console.warn(`Missing environment variable SOLUTION_NAME, using a default value`);
  }

  if (typeof CODE_VERSION !== 'string' || CODE_VERSION.trim() === '') {
    console.warn(`Missing environment variable CODE_VERSION, using a default value`);
  }

  const codeBucket = CODE_BUCKET ?? "Unknown";
  const solutionVersion = CODE_VERSION ?? "Unknown";
  const solutionName = SOLUTION_NAME ?? "MigrationAssistant";
  const stackNameSuffix = STACK_NAME_SUFFIX ?? undefined;
  const solutionId = 'SO0290';
  const description = `(${solutionId}) - The AWS CloudFormation template for deployment of the ${solutionName}. Version ${solutionVersion}`;
  return {
    codeBucket,
    solutionVersion,
    solutionId,
    solutionName,
    description,
    stackNameSuffix
  };
};

const app = new App();
const infraProps = getProps()
const baseImportVPCStackName = "Migration-Assistant-Infra-Import-VPC"
const baseCreateVPCStackName = "Migration-Assistant-Infra-Create-VPC"
new SolutionsInfrastructureStack(app, baseImportVPCStackName, {
  synthesizer: new DefaultStackSynthesizer(),
  createVPC: false,
  stackName: infraProps.stackNameSuffix ? `${baseImportVPCStackName}-${infraProps.stackNameSuffix}` : baseImportVPCStackName,
  ...infraProps
});
new SolutionsInfrastructureStack(app, baseCreateVPCStackName, {
  synthesizer: new DefaultStackSynthesizer(),
  createVPC: true,
  stackName: infraProps.stackNameSuffix ? `${baseCreateVPCStackName}-${infraProps.stackNameSuffix}` : baseCreateVPCStackName,
  ...infraProps
});
