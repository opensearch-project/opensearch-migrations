import 'source-map-support/register';
import { App, DefaultStackSynthesizer } from 'aws-cdk-lib';
import { SolutionsInfrastructureStack } from '../lib/solutions-stack';
import {SolutionsInfrastructureEKSStack} from "../lib/solutions-stack-eks";

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
new SolutionsInfrastructureStack(app, "Migration-Assistant-Infra-Import-VPC", {
  synthesizer: new DefaultStackSynthesizer({
    generateBootstrapVersionRule: false
  }),
  createVPC: false,
  ...infraProps
});
new SolutionsInfrastructureStack(app, "Migration-Assistant-Infra-Create-VPC", {
  synthesizer: new DefaultStackSynthesizer({
    generateBootstrapVersionRule: false
  }),
  createVPC: true,
  ...infraProps
});
new SolutionsInfrastructureEKSStack(app, "Migration-Assistant-Infra-Import-VPC-v3", {
  synthesizer: new DefaultStackSynthesizer({
    generateBootstrapVersionRule: false
  }),
  createVPC: false,
  ...infraProps
});
new SolutionsInfrastructureEKSStack(app, "Migration-Assistant-Infra-Create-VPC-v3", {
  synthesizer: new DefaultStackSynthesizer({
    generateBootstrapVersionRule: false
  }),
  createVPC: true,
  ...infraProps
});
