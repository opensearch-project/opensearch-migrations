import { Construct } from 'constructs';
import { App, Environment, Stack, StackProps } from 'aws-cdk-lib';
import * as fs from 'fs';
import * as path from 'path';
import { ACMClient, ImportCertificateCommand, AddTagsToCertificateCommand } from '@aws-sdk/client-acm';
import { STSClient, GetCallerIdentityCommand } from '@aws-sdk/client-sts';
import * as forge from 'node-forge';


interface ACMImportCertificateStackProps extends StackProps {
  env: Environment;
}

class ACMImportCertificateStack extends Stack {

  constructor(scope: Construct, id: string, props: ACMImportCertificateStackProps) {
    super(scope, id, props);

    const certDir = path.join(__dirname, 'certs');
    if (!fs.existsSync(certDir)) {
      fs.mkdirSync(certDir);
    }

    const certPath = path.join(certDir, 'certificate.pem');
    const keyPath = path.join(certDir, 'privateKey.pem');
    const chainPath = path.join(certDir, 'certificateChain.pem');

    this.generateSelfSignedCertificate(certPath, keyPath, chainPath);

    this.importCertificate(certPath, keyPath, chainPath).then(certificateArn => {
      console.log(`Certificate imported with ARN: ${certificateArn}`);
      this.addTagsToCertificate(certificateArn);
    }).catch(error => {
      console.error('Error importing certificate:', error);
    });
  }

private generateSelfSignedCertificate(certPath: string, keyPath: string, chainPath: string): void {
  const keys = forge.pki.rsa.generateKeyPair(2048);
  const cert = forge.pki.createCertificate();
  
  cert.publicKey = keys.publicKey;
  cert.serialNumber = '01';
  cert.validity.notBefore = new Date(Date.UTC(1970, 0, 1, 0, 0, 0));
  cert.validity.notAfter = new Date(Date.UTC(9999, 11, 31, 23, 59, 59));
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
    name: 'keyUsage',
    keyCertSign: true,
    digitalSignature: true,
    keyEncipherment: true
  }, {
    name: 'extKeyUsage',
    serverAuth: true,
    clientAuth: true
  }]);
  cert.sign(keys.privateKey, forge.md.sha384.create());

  const pemCert = forge.pki.certificateToPem(cert);
  const pemKey = forge.pki.privateKeyToPem(keys.privateKey);

  fs.writeFileSync(certPath, pemCert);
  fs.writeFileSync(keyPath, pemKey);
  fs.writeFileSync(chainPath, pemCert); // For simplicity, we use the same certificate as the chain
}
  private async importCertificate(certPath: string, keyPath: string, chainPath: string): Promise<string> {
    const client = new ACMClient({ region: this.region });

    const certificate = fs.readFileSync(certPath, 'utf8');
    const privateKey = fs.readFileSync(keyPath, 'utf8');
    const certificateChain = fs.readFileSync(chainPath, 'utf8');

    const command = new ImportCertificateCommand({
      Certificate: Buffer.from(certificate),
      PrivateKey: Buffer.from(privateKey),
      CertificateChain: Buffer.from(certificateChain),
    });

    const response = await client.send(command);
    return response.CertificateArn!;
  }

  private async addTagsToCertificate(certificateArn: string): Promise<void> {
    const client = new ACMClient({ region: this.region });
    const currentDate = new Date().toISOString().split('T')[0];

    const command = new AddTagsToCertificateCommand({
      CertificateArn: certificateArn,
      Tags: [
        { Key: 'Name', Value: 'Migration Assistant Certificate' },
      ]
    });

    await client.send(command);
  }
}

async function main() {
  const region = process.argv[2];
  if (!region) {
    throw new Error('A valid AWS region must be specified.');
  }

  const stsClient = new STSClient({ region });
  const identity = await stsClient.send(new GetCallerIdentityCommand({}));
  const account = identity.Account;

  const app = new App();
  new ACMImportCertificateStack(app, 'ACMImportCertificateStack', {
    env: { account, region }
  });
  app.synth();
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});

