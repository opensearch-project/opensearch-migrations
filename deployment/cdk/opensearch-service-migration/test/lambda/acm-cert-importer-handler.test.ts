import { handler } from '../../lib/lambda/acm-cert-importer-handler';
import { CloudFormationCustomResourceCreateEvent, CloudFormationCustomResourceUpdateEvent, CloudFormationCustomResourceDeleteEvent, Context } from 'aws-lambda';
import { ACMClient, ImportCertificateCommand, DeleteCertificateCommand } from '@aws-sdk/client-acm';
import * as forge from 'node-forge';
import * as https from 'https';

jest.mock('@aws-sdk/client-acm');
jest.mock('node-forge');
jest.mock('https');

describe('ACM Certificate Importer Handler', () => {
  let mockContext: Context;

  beforeEach(() => {
    mockContext = {
      callbackWaitsForEmptyEventLoop: false,
      functionName: 'mockFunctionName',
      functionVersion: 'mockFunctionVersion',
      invokedFunctionArn: 'mockInvokedFunctionArn',
      memoryLimitInMB: '128',
      awsRequestId: 'mockAwsRequestId',
      logGroupName: 'mockLogGroupName',
      logStreamName: 'mockLogStreamName',
      getRemainingTimeInMillis: jest.fn(),
      done: jest.fn(),
      fail: jest.fn(),
      succeed: jest.fn()
    };

    process.env.AWS_REGION = 'us-west-2';

    (https.request as jest.Mock).mockImplementation((options, callback) => {
      const mockResponse = {
        statusCode: 200,
        statusMessage: 'OK',
      };
      callback(mockResponse);
      return {
        on: jest.fn(),
        write: jest.fn(),
        end: jest.fn(),
      };
    });
  });

  afterEach(() => {
    jest.clearAllMocks();
    jest.resetModules();
    jest.restoreAllMocks();
  });

  test('Create: should generate and import a self-signed certificate', async () => {
    const mockEvent: CloudFormationCustomResourceCreateEvent = {
      RequestType: 'Create',
      ServiceToken: 'mockServiceToken',
      ResponseURL: 'https://mockurl.com',
      StackId: 'mockStackId',
      RequestId: 'mockRequestId',
      LogicalResourceId: 'mockLogicalResourceId',
      ResourceType: 'Custom::ACMCertificateImporter',
      ResourceProperties: {
        ServiceToken: 'mockServiceToken'
      }
    };

    const mockCertificate = 'mockCertificate';
    const mockPrivateKey = 'mockPrivateKey';
    const mockCertificateArn = 'arn:aws:acm:us-west-2:123456789012:certificate/mock-certificate-id';

    (forge.pki.rsa.generateKeyPair as jest.Mock).mockReturnValue({
      publicKey: 'mockPublicKey',
      privateKey: 'mockPrivateKey'
    });

    const mockCert = {
      publicKey: 'mockPublicKey',
      serialNumber: '01',
      validity: {
        notBefore: new Date(),
        notAfter: new Date()
      },
      setSubject: jest.fn(),
      setIssuer: jest.fn(),
      setExtensions: jest.fn(),
      sign: jest.fn()
    };
    (forge.pki.createCertificate as jest.Mock).mockReturnValue(mockCert);

    (forge.pki.certificateToPem as jest.Mock).mockReturnValue(mockCertificate);
    (forge.pki.privateKeyToPem as jest.Mock).mockReturnValue(mockPrivateKey);

    const mockSendFn = jest.fn().mockResolvedValue({ CertificateArn: mockCertificateArn });
    (ACMClient as jest.Mock).mockImplementation(() => ({
      send: mockSendFn
    }));

    const result = await handler(mockEvent, mockContext);

    expect(result.Status).toBe('SUCCESS');
    expect(result.PhysicalResourceId).toBe(mockCertificateArn);
    expect(result.Data).toEqual({ CertificateArn: mockCertificateArn });
    expect(mockSendFn).toHaveBeenCalledWith(expect.any(ImportCertificateCommand));
    
    expect(forge.pki.rsa.generateKeyPair).toHaveBeenCalledWith(2048);
    expect(mockCert.setSubject).toHaveBeenCalledWith([{ name: 'commonName', value: 'localhost' }]);
    expect(mockCert.setIssuer).toHaveBeenCalledWith([{ name: 'commonName', value: 'localhost' }]);
    expect(mockCert.setExtensions).toHaveBeenCalledWith(expect.arrayContaining([
      { name: 'basicConstraints', cA: true },
      { name: 'subjectAltName', altNames: [{ type: 2, value: 'localhost' }] },
      { name: 'keyUsage', keyCertSign: true, digitalSignature: true, keyEncipherment: true },
      { name: 'extKeyUsage', serverAuth: true, clientAuth: true }
    ]));
    expect(mockCert.sign).toHaveBeenCalledWith(expect.anything(), forge.md.sha1.create());

    // Wait for any pending promises to resolve
    await new Promise(process.nextTick);
  });

  test('Update: should return the existing physical resource id', async () => {
    const mockEvent: CloudFormationCustomResourceUpdateEvent = {
      RequestType: 'Update',
      ServiceToken: 'mockServiceToken',
      ResponseURL: 'https://mockurl.com',
      StackId: 'mockStackId',
      RequestId: 'mockRequestId',
      LogicalResourceId: 'mockLogicalResourceId',
      PhysicalResourceId: 'existingArn',
      ResourceType: 'Custom::ACMCertificateImporter',
      ResourceProperties: {
        ServiceToken: 'mockServiceToken'
      },
      OldResourceProperties: { ServiceToken: 'fake-token' }
    };

    const result = await handler(mockEvent, mockContext);

    expect(result.Status).toBe('SUCCESS');
    expect(result.PhysicalResourceId).toBe('existingArn');
    expect(result.Data).toEqual({});
    expect(ACMClient).not.toHaveBeenCalled();

    // Wait for any pending promises to resolve
    await new Promise(process.nextTick);
  });

  test('Delete: should delete the certificate', async () => {
    const mockEvent: CloudFormationCustomResourceDeleteEvent = {
      RequestType: 'Delete',
      ServiceToken: 'mockServiceToken',
      ResponseURL: 'https://mockurl.com',
      StackId: 'mockStackId',
      RequestId: 'mockRequestId',
      LogicalResourceId: 'mockLogicalResourceId',
      PhysicalResourceId: 'arnToDelete',
      ResourceType: 'Custom::ACMCertificateImporter',
      ResourceProperties: {
        ServiceToken: 'mockServiceToken'
      }
    };

    const mockSendFn = jest.fn().mockResolvedValue({});
    (ACMClient as jest.Mock).mockImplementation(() => ({
      send: mockSendFn
    }));

    const result = await handler(mockEvent, mockContext);

    expect(result.Status).toBe('SUCCESS');
    expect(result.PhysicalResourceId).toBe('arnToDelete');
    expect(result.Data).toEqual({ CertificateArn: 'arnToDelete' });
    expect(mockSendFn).toHaveBeenCalledWith(expect.any(DeleteCertificateCommand));
    expect(mockSendFn).toHaveBeenCalledTimes(1);
    expect(DeleteCertificateCommand).toHaveBeenCalledWith({ CertificateArn: 'arnToDelete' });

    // Wait for any pending promises to resolve
    await new Promise(process.nextTick);
  });
});