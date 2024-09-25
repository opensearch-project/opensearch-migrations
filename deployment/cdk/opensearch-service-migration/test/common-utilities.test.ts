import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {parseClusterDefinition, validateFargateCpuArch} from "../lib/common-utilities";
import {describe, test, expect} from '@jest/globals';

describe('validateFargateCpuArch', () => {
    test('Test valid fargate cpu arch strings can be parsed', () => {
        const cpuArch1 = "arm64"
        const detectedArch1 = validateFargateCpuArch(cpuArch1)
        expect(detectedArch1).toEqual(CpuArchitecture.ARM64)

        const cpuArch2 = "ARM64"
        const detectedArch2 = validateFargateCpuArch(cpuArch2)
        expect(detectedArch2).toEqual(CpuArchitecture.ARM64)

        const cpuArch3 = "x86_64"
        const detectedArch3 = validateFargateCpuArch(cpuArch3)
        expect(detectedArch3).toEqual(CpuArchitecture.X86_64)

        const cpuArch4 = "X86_64"
        const detectedArch4 = validateFargateCpuArch(cpuArch4)
        expect(detectedArch4).toEqual(CpuArchitecture.X86_64)
    })

    test('Test invalid fargate cpu arch strings throws error', () => {
        const cpuArch = "arm32"
        const getArchFunction = () => validateFargateCpuArch(cpuArch)
        expect(getArchFunction).toThrow()
    })

    test('Test detected fargate cpu arch is valid', () => {
        const detectedArch = process.arch
        const detectedArchUpper = detectedArch.toUpperCase()

        const expectedCpuArch = detectedArchUpper === "X64" ? CpuArchitecture.X86_64 : CpuArchitecture.ARM64
        const cpuArch = validateFargateCpuArch()
        expect(cpuArch).toEqual(expectedCpuArch)
    })

    test('parseClusterDefinition with basic auth parameters', () => {
        const clusterDefinition = {
            endpoint: 'https://target-cluster',
            auth: {
              type: 'basic',
              username: 'admin',
              passwordFromSecretArn: 'arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc'
            }
          }
        const parsed = parseClusterDefinition(clusterDefinition);
        expect(parsed).toBeDefined();
        expect(parsed.endpoint).toBe(clusterDefinition.endpoint);
        expect(parsed.auth.basicAuth).toBeDefined();
        expect(parsed.auth.basicAuth?.username).toBe(clusterDefinition.auth.username);
        expect(parsed.auth.basicAuth?.password_from_secret_arn).toBe(clusterDefinition.auth.passwordFromSecretArn);
    })

    test('parseClusterDefinition with no auth', () => {
        const clusterDefinition = {
            endpoint: 'XXXXXXXXXXXXXXXXXXXXXX',
            auth: {"type": "none"}
          }
        const parsed = parseClusterDefinition(clusterDefinition);
        expect(parsed).toBeDefined();
        expect(parsed.endpoint).toBe(clusterDefinition.endpoint);
        expect(parsed.auth.noAuth).toBeDefined();
    })

    test('parseClusterDefinition with sigv4 auth', () => {
        const clusterDefinition = {
            endpoint: 'XXXXXXXXXXXXXXXXXXXXXX',
            auth: {
              type: 'sigv4',
              region: 'us-east-1',
              serviceSigningName: 'es'
            }
          }
        const parsed = parseClusterDefinition(clusterDefinition);
        expect(parsed).toBeDefined();
        expect(parsed.endpoint).toBe(clusterDefinition.endpoint);
        expect(parsed.auth.sigv4).toBeDefined();
        expect(parsed.auth.sigv4?.region).toBe(clusterDefinition.auth.region);
        expect(parsed.auth.sigv4?.serviceSigningName).toBe(clusterDefinition.auth.serviceSigningName);
    })
})
