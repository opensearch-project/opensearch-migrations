import { Construct } from 'constructs';
import { Certificate, ICertificate } from "aws-cdk-lib/aws-certificatemanager";
import { Provider } from 'aws-cdk-lib/custom-resources';
import { NodejsFunction } from 'aws-cdk-lib/aws-lambda-nodejs';
import { CustomResource, Duration, RemovalPolicy, Stack } from 'aws-cdk-lib/core';
import * as path from 'node:path';
import { Runtime } from 'aws-cdk-lib/aws-lambda';
import { PolicyStatement, Role, ServicePrincipal, ManagedPolicy } from 'aws-cdk-lib/aws-iam';
import { LogGroup, RetentionDays } from 'aws-cdk-lib/aws-logs';

export class AcmCertificateImporter extends Construct {
    public readonly acmCert: ICertificate;

    constructor(scope: Construct, id: string, stage: string) {
        super(scope, id);

        // Create a role for the Lambda function with the necessary permissions
        const lambdaRole = new Role(this, 'AcmCertificateImporterRole', {
            assumedBy: new ServicePrincipal('lambda.amazonaws.com'),
        });

        const partition = Stack.of(this).partition;
        lambdaRole.addManagedPolicy(
            ManagedPolicy.fromManagedPolicyArn(
                this,
                'LambdaBasicExecutionRolePolicy',
                `arn:${partition}:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole`
            )
        );
        lambdaRole.addManagedPolicy(
            ManagedPolicy.fromManagedPolicyArn(
                this,
                'LambdaVPCAccessExecutionRolePolicy',
                `arn:${partition}:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole`
            )
        );
        lambdaRole.addToPolicy(new PolicyStatement({
            actions: ['acm:ImportCertificate', 'acm:DeleteCertificate'],
            resources: ['*'],
        }));
        
        const onEvent = new NodejsFunction(this, 'AcmCertificateImporterHandler', {
            runtime: Runtime.NODEJS_20_X,
            handler: 'handler',
            entry: path.join(__dirname, '../lambda/acm-cert-importer-handler.ts'),
            environment: {
            },
            timeout: Duration.seconds(30),
            role: lambdaRole,
            logGroup: new LogGroup(this, 'AcmCertificateImporterLogGroup', {
                logGroupName: `/aws/lambda/${stage}/AcmCertificateImporterHandler-${id}`,
                removalPolicy: RemovalPolicy.DESTROY,
                retention: RetentionDays.ONE_DAY
            })
        });

        const myProvider = new Provider(this, 'AcmCertificateImporterProvider'+id, {
            onEventHandler: onEvent,
        });

        const resource = new CustomResource(this, 'AcmCertificateImporterResource' + id, {
            serviceToken: myProvider.serviceToken,
        });
        const acmCertArn = resource.ref;
        this.acmCert = Certificate.fromCertificateArn(this, "ImportedCert"+id, acmCertArn);
    }
}
