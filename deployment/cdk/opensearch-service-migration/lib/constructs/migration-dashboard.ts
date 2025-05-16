import { Construct } from 'constructs';
import { CfnDashboard } from 'aws-cdk-lib/aws-cloudwatch';

interface DashboardVariable {
    id: string;
    defaultValue: string;
}

interface DashboardBody {
    variables: DashboardVariable[];
}

export interface MigrationDashboardProps {
    readonly dashboardQualifier: string;
    readonly stage: string;
    readonly account: string;
    readonly region: string;
    readonly dashboardJson: DashboardBody;
}

export class MigrationDashboard extends Construct {
    constructor(scope: Construct, props: MigrationDashboardProps) {
        const dashboardName = `MigrationAssistant_${props.dashboardQualifier}_${props.stage}_${props.region}`
        super(scope, dashboardName);

        const dashboard = this.setDashboardVariables(props.dashboardJson, props);
        new CfnDashboard(this, dashboardName, {
            dashboardName: dashboardName,
            dashboardBody: JSON.stringify(dashboard)
        });
    }

    private setDashboardVariables(dashboard: DashboardBody, props: MigrationDashboardProps): DashboardBody {
        const variables = dashboard.variables;
        
        const variableSetters = {
            'ACCOUNT_ID': props.account,
            'REGION': props.region,
            'MA_STAGE': props.stage,
            'MA_QUALIFIER': props.stage
        };

        Object.entries(variableSetters).forEach(([varName, value]) => {
            const variable = variables.find(v => v.id === varName);
            if (variable) {
                variable.defaultValue = value;
            }
        });

        return dashboard;
    }
}
