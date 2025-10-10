import {
  Context,
  CloudFormationCustomResourceEvent,
  CloudFormationCustomResourceResponse,
} from 'aws-lambda';
import { ACMClient, ImportCertificateCommand, DeleteCertificateCommand } from '@aws-sdk/client-acm';
import * as https from 'node:https';
import * as forge from 'node-forge';

export const handler = async (event: CloudFormationCustomResourceEvent, context: Context): Promise<CloudFormationCustomResourceResponse> => {
  console.log('Received event:', JSON.stringify(event, null, 2));
  console.log('Received context:', JSON.stringify(context, null, 2));
  let responseData: { CertificateArn?: string } = {};
  let physicalResourceId = '';

  try {
    switch (event.RequestType) {
      case 'Create': {
        const { certificate, privateKey, certificateChain } = await generateSelfSignedCertificate();
        const certificateArn = await importCertificate(certificate, privateKey, certificateChain);
        console.log(`Certificate imported with ARN: ${certificateArn}`);
        responseData = { CertificateArn: certificateArn };
        physicalResourceId = certificateArn;
        break;
      }
      case 'Update': {
        // No update logic needed, return existing physical resource id
        physicalResourceId = event.PhysicalResourceId;
        break;
      }
      case 'Delete': {
        const arn = event.PhysicalResourceId;
        await deleteCertificate(arn);
        responseData = { CertificateArn: arn };
        physicalResourceId = arn;
        break;
      }
    }

    return await sendResponse(event, context, 'SUCCESS', responseData, physicalResourceId);
  } catch (error) {
    console.error(error);
    return await sendResponse(event, context, 'FAILED', { Error: (error as Error).message }, physicalResourceId);
  }
};

async function generateSelfSignedCertificate(): Promise<{ certificate: string, privateKey: string, certificateChain: string }> {
  return new Promise((resolve) => {
    const keys = forge.pki.rsa.generateKeyPair(2048);
    const cert = forge.pki.createCertificate();

    cert.publicKey = keys.publicKey;
    cert.serialNumber = '01';
    cert.validity.notBefore = new Date(Date.UTC(1970, 0, 1, 0, 0, 0));
    cert.validity.notAfter = new Date(Date.UTC(9999, 11, 31, 23, 59, 59, 999));
    const attrs = [{
      name: 'commonName',
      value: 'localhost'
    }];

    cert.setSubject(attrs);
    cert.setIssuer(attrs);

    cert.setExtensions([{
      name: 'basicConstraints',
      cA: true
    }, {
      name: 'subjectAltName',
      altNames: [{
        type: 2, // DNS
        value: 'localhost'
      }]
    }, {
      name: 'keyUsage',
      keyCertSign: true,
      digitalSignature: true,
      keyEncipherment: true
    }, {
      name: 'extKeyUsage',
      serverAuth: true,
      clientAuth: true
    },]);
    cert.sign(keys.privateKey, forge.md.sha384.create());

    const pemCert = forge.pki.certificateToPem(cert);
    const pemKey = forge.pki.privateKeyToPem(keys.privateKey);

    resolve({
      certificate: pemCert,
      privateKey: pemKey,
      certificateChain: pemCert
    });
  });
}

async function importCertificate(certificate: string, privateKey: string, certificateChain: string): Promise<string> {
  const client = new ACMClient({ region: process.env.AWS_REGION });

  const command = new ImportCertificateCommand({
    Certificate: Buffer.from(certificate),
    PrivateKey: Buffer.from(privateKey),
    CertificateChain: Buffer.from(certificateChain),
  });

  const response = await client.send(command);
  if (!response.CertificateArn) {
    throw new Error(`Unexpected response, no certificate arn in response`);
  }
  return response.CertificateArn;
}

async function deleteCertificate(certificateArn: string): Promise<void> {
  const client = new ACMClient({ region: process.env.AWS_REGION });
  const command = new DeleteCertificateCommand({
    CertificateArn: certificateArn,
  });

  await client.send(command);
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function sendResponse(event: CloudFormationCustomResourceEvent, context: Context, responseStatus: string, responseData: Record<string, any>, physicalResourceId: string): Promise<CloudFormationCustomResourceResponse> {
  const responseBody = JSON.stringify({
    Status: responseStatus,
    Reason: `See the details in CloudWatch Log Stream: ${context.logStreamName}`,
    PhysicalResourceId: physicalResourceId,
    StackId: event.StackId,
    RequestId: event.RequestId,
    LogicalResourceId: event.LogicalResourceId,
    Data: responseData
  });

  console.log('Response body:', responseBody);

  if (event.ResponseURL === "Local") {
    console.log('Running locally, simulating response success.');
    return {
      Status: 'SUCCESS',
      Reason: 'Running locally, response simulated.',
      PhysicalResourceId: physicalResourceId,
      StackId: event.StackId,
      RequestId: event.RequestId,
      LogicalResourceId: event.LogicalResourceId,
      Data: responseData
    };
  }

  const parsedUrl = new URL(event.ResponseURL);
  const options = {
    hostname: parsedUrl.hostname,
    port: 443,
    path: parsedUrl.pathname + parsedUrl.search,
    method: 'PUT',
    headers: {
      'Content-Type': '',
      'Content-Length': responseBody.length
    }
  };

  return new Promise<CloudFormationCustomResourceResponse>((resolve, reject) => {
    const request = https.request(options, (response) => {
      console.log(`Status code: ${response.statusCode}`);
      console.log(`Status message: ${response.statusMessage}`);
      resolve({
        Status: responseStatus === 'SUCCESS' || responseStatus === 'FAILED' ? responseStatus : 'FAILED',
        Reason: `See the details in CloudWatch Log Stream: ${context.logStreamName}`,
        PhysicalResourceId: physicalResourceId,
        StackId: event.StackId,
        RequestId: event.RequestId,
        LogicalResourceId: event.LogicalResourceId,
        Data: responseData
      });
    });

    request.on('error', (error: Error) => {
      console.error('sendResponse Error:', error);
      reject(error);
    });

    request.write(responseBody);
    request.end();
  });
}
