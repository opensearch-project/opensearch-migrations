import { CfnDashboard } from 'aws-cdk-lib/aws-cloudwatch';
import { Construct } from 'constructs';
import * as fs from 'fs';
import * as path from 'path';

export interface CaptureReplayDashboardProps {
  readonly stage: string;
}

export class CaptureReplayDashboard extends Construct {
  public readonly dashboard: CfnDashboard;

  constructor(scope: Construct, id: string, props: CaptureReplayDashboardProps) {
    super(scope, id);

    // Read the dashboard JSON from file
    const dashboardJsonPath = path.join(__dirname, 'migrationassistant-capture-replay-dashboard.json');
    let dashboardJson;
    
    try {
      const fileContent = fs.readFileSync(dashboardJsonPath, 'utf8');
      dashboardJson = JSON.parse(fileContent);
      
      // Inject stage parameter into all metrics
      this.injectStageParameter(dashboardJson, props.stage);
      
    } catch (error) {
      // Fallback to default dashboard if JSON file is missing or invalid
      console.warn(`Failed to load dashboard JSON from ${dashboardJsonPath}: ${error}. Using default dashboard.`);
      dashboardJson = this.createDefaultDashboard(props.stage);
    }

    // Create the dashboard
    this.dashboard = new CfnDashboard(this, 'Dashboard', {
      dashboardName: `CaptureReplay-Dashboard-${props.stage}`,
      dashboardBody: JSON.stringify(dashboardJson)
    });
  }

  // Injects the stage parameter into all metrics in the dashboard JSON
  private injectStageParameter(dashboardJson: any, stage: string): void {
    // Process each widget in the dashboard
    if (dashboardJson.widgets) {
      dashboardJson.widgets.forEach((widget: any) => {
        if (widget.properties && widget.properties.metrics) {
          // For each metric in the widget, ensure it has the Stage dimension
          widget.properties.metrics.forEach((metric: any) => {
            // Handle both array and object metric formats
            if (Array.isArray(metric)) {
              // Array format: ["Namespace", "MetricName", {dimensions}]
              const lastItem = metric[metric.length - 1];
              if (typeof lastItem === 'object') {
                if (!lastItem.dimensions) {
                  lastItem.dimensions = {};
                }
                lastItem.dimensions.Stage = stage;
              } else {
                // Add dimensions object if not present
                metric.push({ dimensions: { Stage: stage } });
              }
            } else if (typeof metric === 'object') {
              // Object format: {namespace, metricName, dimensions}
              if (!metric.dimensions) {
                metric.dimensions = {};
              }
              metric.dimensions.Stage = stage;
            }
          });
        }
      });
    }
  }

  // Creates a default dashboard with basic metrics if JSON file is not available
  private createDefaultDashboard(stage: string): any {
    return {
      widgets: [
        {
          type: "text",
          width: 24,
          height: 2,
          properties: {
            markdown: "# Capture & Replay Migration Progress\nThis dashboard shows the progress and metrics of your Capture & Replay migration."
          }
        },
        {
          type: "metric",
          width: 12,
          height: 6,
          properties: {
            metrics: [
              ["OpenSearchMigrations/CaptureReplay", "CapturedRequests", { stat: "Sum", label: "Total Requests Captured", dimensions: { Stage: stage } }],
              [".", "CaptureErrors", { stat: "Sum", label: "Capture Errors", dimensions: { Stage: stage } }]
            ],
            view: "timeSeries",
            stacked: false,
            title: "Capture Progress"
          }
        },
        {
          type: "metric",
          width: 12,
          height: 6,
          properties: {
            metrics: [
              ["OpenSearchMigrations/CaptureReplay", "ReplayedRequests", { stat: "Sum", label: "Total Requests Replayed", dimensions: { Stage: stage } }],
              [".", "ReplayErrors", { stat: "Sum", label: "Replay Errors", dimensions: { Stage: stage } }]
            ],
            view: "timeSeries",
            stacked: false,
            title: "Replay Progress"
          }
        }
      ]
    };
  }
}
