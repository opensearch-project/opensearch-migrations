import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {validateFargateCpuArch} from "../lib/common-utilities";
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
})