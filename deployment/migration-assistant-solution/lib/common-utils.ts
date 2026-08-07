import {CfnParameter, Stack} from "aws-cdk-lib";
import {InterfaceVpcEndpointAwsService} from "aws-cdk-lib/aws-ec2";

export const SOLUTION_ID = 'SO0290';
export const SOLUTION_FRIENDLY_NAME = 'Migration Assistant for Amazon OpenSearch Service';
export const SOLUTION_VERSION: string = process.env.CODE_VERSION || '2.0.0';

export type TemplateKind =
  | 'ECS_VPC_CREATE'
  | 'ECS_VPC_IMPORT'
  | 'EKS_VPC_CREATE'
  | 'EKS_VPC_IMPORT';

export function buildTemplateDescription(kind: TemplateKind): string {
    const base = `(${SOLUTION_ID}) - ${SOLUTION_FRIENDLY_NAME}`;

    const label = (() => {
        switch (kind) {
            case 'ECS_VPC_CREATE':
                return 'ECS VPC Create Template';
            case 'ECS_VPC_IMPORT':
                return 'ECS VPC Import Template';
            case 'EKS_VPC_CREATE':
                return 'EKS VPC Create Template';
            case 'EKS_VPC_IMPORT':
                return 'EKS VPC Import Template';
        }
    })();

    // Example:
    // "(SO0290) - Migration Assistant for Amazon OpenSearch Service - ECS VPC Create Template v2.5.15"
    return `${base} - ${label} v${SOLUTION_VERSION}`;
}

export interface ParameterLabel {
    default: string;
}

export function addParameterLabel(labels: Record<string, ParameterLabel>, parameter: CfnParameter, labelName: string) {
    labels[parameter.logicalId] = {"default": labelName}
}

export function generateExportString(exports:  Record<string, string>): string {
    return Object.entries(exports)
        .map(([key, value]) => `export ${key}=${value}`)
        .join("; ");
}

export function getVpcEndpointForEFS(stack: Stack): InterfaceVpcEndpointAwsService {
    const isGovRegion = stack.region?.startsWith('us-gov-')
    if (isGovRegion) {
        return InterfaceVpcEndpointAwsService.ELASTIC_FILESYSTEM_FIPS;
    }
    return InterfaceVpcEndpointAwsService.ELASTIC_FILESYSTEM;
}
