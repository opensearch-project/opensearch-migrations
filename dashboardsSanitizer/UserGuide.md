# Migration Guide: Transforming ElasticSearch Dashboards to OpenSearch

This guide provides detailed instructions for using the Sanitizer tool to migrate your existing ElasticSearch dashboards to OpenSearch. Follow these steps to ensure a smooth and accurate migration process.

## 1. Prerequisites

### 1.1. Environment Setup

- **ElasticSearch and Kibana**:
  - Ensure you have a compatible version of ElasticSearch and Kibana installed. Refer to the Sanitizer tool documentation for supported versions.
  - Both ElasticSearch and Kibana should be properly configured and running.

- **OpenSearch**:
  - Ensure OpenSearch is installed and running.
  - Verify that the version of OpenSearch you are using is compatible with the migrated dashboards.

- **Sanitizer Tool**:
  - Obtain the latest version of the Sanitizer tool. Ensure you have access to the most recent release.
  - Confirm that the Sanitizer tool is compatible with your versions of ElasticSearch, Kibana, and OpenSearch.

- **Credentials**:
  - Obtain the necessary credentials for accessing ElasticSearch/Kibana and OpenSearch.
  - Ensure you have the appropriate permissions to perform migrations and access your data.

## 2. Export Dashboards from ElasticSearch

1. **Navigate to Saved Objects**:
   - In Kibana, go to **Management > Kibana > Saved Objects**. The exact path may vary depending on your ElasticSearch and Kibana versions.

2. **Select Dashboards for Export**:
   - Choose the dashboards you want to migrate from the list of saved objects.

3. **Export Dashboards**:
   - Click the **Export** button.
   - Ensure that the 'Include related objects' checkbox is selected to include all related components (e.g., visualizations, index patterns).
   - Save the generated file in NDJSON format.

## 3. Sanitize the Output with the Tool

1. **Run the Dashboard Sanitizer**:
   - Execute the following command to run the dashboard sanitizer on the exported file:
     ```bash
     ./gradlew dashboardsSanitizer:run --args='--source opensearch-export.ndjson --output sanitized.ndjson'
     ```
   - This command processes the source file and generates an OpenSearch-compatible output.

2. **Review Sanitization Statistics**:
   - After running the command, review the provided statistics, which will look like:
     ```json
     {
       "total" : 49,
       "processed" : 41,
       "skipped" : {
         "count" : 8,
         "details" : {
           "canvas-workpad" : 2,
           "lens" : 2,
           "config" : 4
         }
       }
     }
     ```
   - These statistics give insights into the quality of the transformation, including the number of items processed and any skipped, along with details on skipped items.

## 4. Import the Transformed Output into OpenSearch

1. **Ensure Index Creation and Configuration**:
   - Verify that the index in OpenSearch is created and configured to match the index setup from ElasticSearch.
   - Confirm that all necessary mappings, settings, and configurations are in place.

2. **Verify Test Data Availability**:
   - Ensure that the test data is properly imported into the OpenSearch index.
   - Check that the data is available and correctly indexed; otherwise, there will be no information for the dashboards to present.

3. **Navigate to Saved Objects**:
   - In OpenSearch, go to **Management > Dashboard Management > Saved Objects**.

4. **Import the Sanitized File**:
   - Click on **Import** and select the sanitized file (`sanitized.ndjson`).
   - Follow the prompts to complete the import process.

## 5. Verify the Dashboard

1. **Verify Each Imported Dashboard**:
   - For each imported dashboard in OpenSearch, check that it displays correctly.
   - Ensure that the dashboard has the same functionality and provides the same information as the original ElasticSearch dashboard.
   - Repeat this verification for each dashboard to confirm accuracy and consistency.

## 6. Post-Migration Activities

1. **Verify for Index or Data Discrepancies**:
   - If an error is detected, first check that the issue is not due to discrepancies in the index configuration, index pattern, or the data.
   - Ensure that the index mappings, settings, and data are consistent and correctly set up.

2. **Contact Support if Necessary**:
   - If you encounter issues that you cannot resolve or if the problem persists despite verifying the index and data, contact the OpenSearch support team for assistance.
   - Provide detailed information about the issue, including error messages, steps to reproduce, and any relevant logs.

## Conclusion

This guide provides a structured approach to migrating your ElasticSearch dashboards to OpenSearch using the Sanitizer tool. By following these steps, you can ensure a smooth and accurate migration process.

