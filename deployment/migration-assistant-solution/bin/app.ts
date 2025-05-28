import 'source-map-support/register';
import { App, DefaultStackSynthesizer } from 'aws-cdk-lib';
import { SolutionsInfrastructureStack } from '../lib/solutions-stack';
import {SolutionsInfrastructureEKSStack} from "../lib/solutions-stack-eks";

const getProps = () => {
  const { CODE_BUCKET, SOLUTION_NAME, CODE_VERSION, STACK_NAME_SUFFIX, EKS_ENABLED } = process.env;
  if (typeof CODE_BUCKET !== 'string' || CODE_BUCKET.trim() === '') {
    console.warn(`Missing environment variable CODE_BUCKET, using a default value`);
  }

  if (typeof SOLUTION_NAME !== 'string' || SOLUTION_NAME.trim() === '') {
    console.warn(`Missing environment variable SOLUTION_NAME, using a default value`);
  }

  if (typeof CODE_VERSION !== 'string' || CODE_VERSION.trim() === '') {
    console.warn(`Missing environment variable CODE_VERSION, using a default value`);
  }

  let eksEnabled = false
  if (typeof EKS_ENABLED === 'string' && EKS_ENABLED.trim() === 'true') {
    eksEnabled = true
    console.warn(`The environment variable EKS_ENABLED=true has been provided, will only create the EKS solutions stack`);
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
    stackNameSuffix,
    eksEnabled
  };
};

const app = new App();
const infraProps = getProps()
if (!infraProps.eksEnabled) {
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
} else {
  new SolutionsInfrastructureEKSStack(app, "Migration-Assistant-Infra-Create-VPC", {
    synthesizer: new DefaultStackSynthesizer({
      generateBootstrapVersionRule: false
    }),
    createVPC: true,
    ...infraProps
  });
}
