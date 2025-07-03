import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {
    appendArgIfNotInExtraArgs,
    ClusterType,
    parseArgsToDict,
    parseClusterDefinition,
    validateAndReturnFormattedHttpURL,
    validateFargateCpuArch
} from "../lib/common-utilities";
import {describe, expect, test} from '@jest/globals';
import {App, Stack} from "aws-cdk-lib";

describe('appendArgIfNotInExtraArgs', () => {

    // Test when the arg is not present in extraArgsDict and has a value
    test('appends arg and value when arg is not in extraArgsDict', () => {
        const baseCommand = 'command';
        const extraArgsDict = {
            "--arg1": ["value1"]
        };
        const result = appendArgIfNotInExtraArgs(baseCommand, extraArgsDict, '--arg2', 'value2');
        expect(result).toBe('command --arg2 value2');
    });

    // Test when the arg is not present in extraArgsDict and has no value
    test('appends arg without value when arg is not in extraArgsDict', () => {
        const baseCommand = 'command';
        const extraArgsDict = {
            "--arg1": ["value1"]
        };
        const result = appendArgIfNotInExtraArgs(baseCommand, extraArgsDict, '--flag');
        expect(result).toBe('command --flag');
    });

    // Test when the arg is already present in extraArgsDict (should not append)
    test('does not append arg and value when arg is in extraArgsDict', () => {
        const baseCommand = 'command';
        const extraArgsDict = {
            "--arg1": ["value1"]
        };
        const result = appendArgIfNotInExtraArgs(baseCommand, extraArgsDict, '--arg1', 'value1');
        expect(result).toBe('command');  // baseCommand should remain unchanged
    });

    // Test when extraArgsDict is empty (should append arg and value)
    test('appends arg and value when extraArgsDict is empty', () => {
        const baseCommand = 'command';
        const extraArgsDict = {};
        const result = appendArgIfNotInExtraArgs(baseCommand, extraArgsDict, '--arg1', 'value1');
        expect(result).toBe('command --arg1 value1');
    });

    // Test when extraArgsDict is empty and arg has no value (should append only arg)
    test('appends only arg when extraArgsDict is empty and value is null', () => {
        const baseCommand = 'command';
        const extraArgsDict = {};
        const result = appendArgIfNotInExtraArgs(baseCommand, extraArgsDict, '--flag');
        expect(result).toBe('command --flag');
    });
});

describe('parseArgsToDict', () => {

    // Test valid input with multiple arguments
    test('parses valid input with multiple arguments', () => {
        const input = "--valid-arg some value --another-arg more values";
        const expectedOutput = {
            "--valid-arg": ["some value"],
            "--another-arg": ["more values"]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Test valid input with special characters in values
    test('parses arguments with special characters in values', () => {
        const input = "--valid-arg some!@--#$%^&*() value --another-arg value with spaces";
        const expectedOutput = {
            "--valid-arg": ["some!@--#$%^&*() value"],
            "--another-arg": ["value with spaces"]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Test when there are multiple spaces between argument and value
    test('parses input with multiple spaces between argument and value', () => {
        const input = "--valid-arg    some value with spaces --another-arg   more   spaces";
        const expectedOutput = {
            "--valid-arg": ["some value with spaces"],
            "--another-arg": ["more   spaces"]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Test input with no value after an argument
    test('handles argument with no value', () => {
        const input = "--valid-arg --another-arg some value";
        const expectedOutput = {
            "--valid-arg": [""],
            "--another-arg": ["some value"]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Test input with argument at the start of the string
    test('parses input with argument at the start of the string', () => {
        const input = "--valid-arg start value --another-arg after value";
        const expectedOutput = {
            "--valid-arg": ["start value"],
            "--another-arg": ["after value"]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Test input with argument preceded by spaces
    test('parses input where arguments are preceded by spaces', () => {
        const input = "   --valid-arg start value  --another-arg after value";
        const expectedOutput = {
            "--valid-arg": ["start value"],
            "--another-arg": ["after value"]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Test input with empty string
    test('returns empty object for empty input', () => {
        const input = "";
        const expectedOutput = {};
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Test input with argument --
    test('throws error for argument --', () => {
        const input = "-- invalid arg some value";
        expect(() => parseArgsToDict(input)).toThrow("Invalid argument key: '--'. Argument keys must start with '--' and contain no spaces.");
    });

    // Test input with missing argument flag
    test('throws error for missing argument flag', () => {
        const input = "valid-arg some value";
        expect(() => parseArgsToDict(input)).toThrow("Invalid argument key: 'valid-arg'. Argument keys must start with '--' and contain no spaces.");
    });

    // Test valid input with multiple special characters and whitespace
    test('handles multiple spaces and special characters in value', () => {
        const input = "--arg1 value with @#$%^&*! --arg2    multiple    spaces";
        const expectedOutput = {
            "--arg1": ["value with @#$%^&*!"],
            "--arg2": ["multiple    spaces"]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Test input with leading and trailing whitespace
    test('trims leading and trailing whitespace from arguments and values', () => {
        const input = "   --valid-arg  some value with spaces   --another-arg   more values  ";
        const expectedOutput = {
            "--valid-arg": ["some value with spaces"],
            "--another-arg": ["more values"]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Test input with only flags, no values
    test('handles input with only flags and no values', () => {
        const input = "--flag1 --flag2";
        const expectedOutput = {
            "--flag1": [""],
            "--flag2": [""]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Test input with no space between flag and value
    test('handles input with no space between flag and value', () => {
        const input = "--flag1value --flag2";
        const expectedOutput = {
            "--flag1value": [""],
            "--flag2": [""]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Handles multiple occurrences of the same key
    test('handles multiple occurrences of the same key', () => {
        const input = "--key1 value1 --key1 value2 --key2 value3";
        const expectedOutput = {
            "--key1": ["value1", "value2"],
            "--key2": ["value3"]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Handles multiple occurrences of the same key with empty values
    test('handles multiple occurrences of the same key with empty values', () => {
        const input = "--key1 --key1 value2 --key2 value3";
        const expectedOutput = {
            "--key1": ["", "value2"],
            "--key2": ["value3"]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });

    // Handles multiple occurrences of different keys
    test('handles multiple occurrences of different keys', () => {
        const input = "--key1 value1 --key2 value2 --key1 value3 --key2 value4";
        const expectedOutput = {
            "--key1": ["value1", "value3"],
            "--key2": ["value2", "value4"]
        };
        expect(parseArgsToDict(input)).toEqual(expectedOutput);
    });
});

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
            endpoint: 'https://target-cluster:443',
            version: 'ES_7.10',
            auth: {
              type: 'basic',
              userSecretArn: 'arn:aws:secretsmanager:us-east-1:12345678912:secret:master-user-os-pass-123abc'
            }
        }
        const scope = new Stack(new App(), 'TestStack');
        const parsed = parseClusterDefinition(clusterDefinition, ClusterType.SOURCE, scope, "test-stage", "default");
        expect(parsed).toBeDefined();
        expect(parsed.endpoint).toBe(clusterDefinition.endpoint);
        expect(parsed.version).toBe(clusterDefinition.version);
        expect(parsed.auth.basicAuth).toBeDefined();
        expect(parsed.auth.basicAuth?.user_secret_arn).toBe(clusterDefinition.auth.userSecretArn);
    })

    test('parseClusterDefinition with no auth', () => {
        const clusterDefinition = {
            endpoint: 'https://vpc-domain-abcdef.us-east-1.es.amazonaws.com:443',
            auth: {"type": "none"}
        }
        const scope = new Stack(new App(), 'TestStack');
        const parsed = parseClusterDefinition(clusterDefinition, ClusterType.TARGET, scope, "test-stage", "default");
        expect(parsed).toBeDefined();
        expect(parsed.endpoint).toBe(clusterDefinition.endpoint);
        expect(parsed.auth.noAuth).toBeDefined();
    })

    test('parseClusterDefinition with sigv4 auth', () => {
        const clusterDefinition = {
            endpoint: 'https://vpc-domain-abcdef.us-east-1.es.amazonaws.com:443',
            auth: {
              type: 'sigv4',
              region: 'us-east-1',
              serviceSigningName: 'es'
            }
          }
        const scope = new Stack(new App(), 'TestStack');
        const parsed = parseClusterDefinition(clusterDefinition, ClusterType.TARGET, scope, "test-stage", "default");
        expect(parsed).toBeDefined();
        expect(parsed.endpoint).toBe(clusterDefinition.endpoint);
        expect(parsed.auth.sigv4).toBeDefined();
        expect(parsed.auth.sigv4?.region).toBe(clusterDefinition.auth.region);
        expect(parsed.auth.sigv4?.serviceSigningName).toBe(clusterDefinition.auth.serviceSigningName);
    })

    test('Test valid https imported target cluster endpoint with port is formatted correctly', () => {
        const inputTargetEndpoint = "https://vpc-domain-abcdef.us-east-1.es.amazonaws.com:443"
        const expectedFormattedEndpoint = "https://vpc-domain-abcdef.us-east-1.es.amazonaws.com:443"
        const actualFormatted = validateAndReturnFormattedHttpURL(inputTargetEndpoint)
        expect(actualFormatted).toEqual(expectedFormattedEndpoint)
    });

    test('Test valid https imported target cluster endpoint with no port is formatted correctly', () => {
        const inputTargetEndpoint = "https://vpc-domain-abcdef.us-east-1.es.amazonaws.com"
        const expectedFormattedEndpoint = "https://vpc-domain-abcdef.us-east-1.es.amazonaws.com:443"
        const actualFormatted = validateAndReturnFormattedHttpURL(inputTargetEndpoint)
        expect(actualFormatted).toEqual(expectedFormattedEndpoint)
    });

    test('Test valid http imported target cluster endpoint with no port is formatted correctly', () => {
        const inputTargetEndpoint = "http://vpc-domain-abcdef.us-east-1.es.amazonaws.com"
        const expectedFormattedEndpoint = "http://vpc-domain-abcdef.us-east-1.es.amazonaws.com:80"
        const actualFormatted = validateAndReturnFormattedHttpURL(inputTargetEndpoint)
        expect(actualFormatted).toEqual(expectedFormattedEndpoint)
    });

    test('Test valid imported target cluster endpoint ending in slash is formatted correctly', () => {
        const inputTargetEndpoint = "https://vpc-domain-abcdef.us-east-1.es.amazonaws.com/"
        const expectedFormattedEndpoint = "https://vpc-domain-abcdef.us-east-1.es.amazonaws.com:443"
        const actualFormatted = validateAndReturnFormattedHttpURL(inputTargetEndpoint)
        expect(actualFormatted).toEqual(expectedFormattedEndpoint)
    });

    test('Test valid imported target cluster endpoint having port and ending in slash is formatted correctly', () => {
        const inputTargetEndpoint = "https://vpc-domain-abcdef.us-east-1.es.amazonaws.com:443/"
        const expectedFormattedEndpoint = "https://vpc-domain-abcdef.us-east-1.es.amazonaws.com:443"
        const actualFormatted = validateAndReturnFormattedHttpURL(inputTargetEndpoint)
        expect(actualFormatted).toEqual(expectedFormattedEndpoint)
    });

    test('Test target cluster endpoint with no protocol throws error', () => {
        const inputTargetEndpoint = "vpc-domain-abcdef.us-east-1.es.amazonaws.com:443/"
        const validateAndFormatURL = () => validateAndReturnFormattedHttpURL(inputTargetEndpoint)
        expect(validateAndFormatURL).toThrow()
    });

    test('Test target cluster endpoint with path throws error', () => {
        const inputTargetEndpoint = "https://vpc-domain-abcdef.us-east-1.es.amazonaws.com:443/indexes"
        const validateAndFormatURL = () => validateAndReturnFormattedHttpURL(inputTargetEndpoint)
        expect(validateAndFormatURL).toThrow()
    });

    test('Test invalid target cluster endpoint throws error', () => {
        const inputTargetEndpoint = "vpc-domain-abcdef"
        const validateAndFormatURL = () => validateAndReturnFormattedHttpURL(inputTargetEndpoint)
        expect(validateAndFormatURL).toThrow()
    });
})
