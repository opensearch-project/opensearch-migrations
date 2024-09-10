# End-to-End Verification Run Book: ElasticSearch/Kibana to OpenSearch Migration

## Purpose

This run book provides step-by-step instructions for performing end-to-end verification of the migration library that transforms ElasticSearch/Kibana dashboards to OpenSearch equivalents. It ensures that the migration tool functions correctly and accurately.

## 1. Prerequisites

### 1.1. **Environment Setup**

- **ElasticSearch and Kibana**:
  - Ensure that the versions of ElasticSearch and Kibana are compatible with the migration library. Refer to the migration library documentation for supported versions.
  - Both ElasticSearch and Kibana should be properly installed and running.

- **OpenSearch**:
  - Ensure OpenSearch is installed and running.
  - Verify that the version of OpenSearch is compatible with the migrated dashboards.

- **Migration Library**:
  - Obtain the latest version of the migration library. Ensure you have access to the latest release, which can be downloaded or accessed as needed.
  - Confirm that the migration library is compatible with the versions of ElasticSearch, Kibana, and OpenSearch being used.

- **Credentials**:
  - Obtain necessary credentials for accessing ElasticSearch/Kibana and OpenSearch.
  - Ensure that you have the required permissions to perform migrations and access the data.

## 2. Prepare the Environment

### 2.1. **Create and Configure Index**

1. **Create an Index**:
   - Use your ElasticSearch/Kibana environment to create a new index. This index will be used to store and manage the test data.
   - Ensure that the index is set up with the correct mappings and settings required for the dashboard.

2. **Import Test Data**:
   - Import a test dataset into the newly created index. This dataset should reflect the types of data that will be used in real-world scenarios.
   - Verify that the data is correctly indexed and accessible.

### 2.2. **Configure Index Pattern**

1. **Create an Index Pattern**:
   - In Kibana, create an index pattern that matches the newly created index. This pattern will allow Kibana to recognize and interact with the test data.
   - Ensure that the index pattern is correctly configured with the appropriate fields and data types.

### 2.3. **Prepare Sample Dashboards**

1. **Create Sample Dashboards**:
   - Design and create a set of sample dashboards in Kibana based on the test dataset. Include various elements such as visualizations, filters, and queries to cover a range of use cases.
   - Ensure that these dashboards represent different complexity levels and features that will be tested.

2. **Verify Dashboard Configuration**:
   - Validate that the dashboards are correctly configured and display the intended data and visualizations.
   - Confirm that all interactive elements (e.g., filters, date ranges) function as expected.

## 3. Export Dashboard from ElasticSearch

1. **Navigate to Saved Objects**:
   - Go to Kibana and navigate to **Management > Kibana > Saved Objects**. Note that this path may vary slightly depending on the ElasticSearch and Kibana versions you are using.

2. **Select Dashboards for Export**:
   - From the list of saved objects, select the dashboards you want to test.

3. **Export Dashboards**:
   - Click the **Export** button.
   - Ensure that the 'Include related objects' checkbox is selected to include all related components (e.g., visualizations, index patterns).
   - Save the generated file in the NDJSON format.

## 4. Sanitize the Output with the Tool

1. **Run the Dashboard Sanitizer**:
   - Execute the following command to run the dashboard sanitizer on the downloaded file:
     ```bash
     ./gradlew dashboardsSanitizer:run --args='--source opensearch-export.ndjson --output sanitized.ndjson'
     ```
   - This command transforms the source file and generates an OpenSearch-compatible output.

2. **Review Sanitization Statistics**:
   - After running the command, review the provided statistics, which will be in the form of:
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
   - These statistics provide insights into the quality of the transformation, including the number of items processed and any that were skipped, along with details on the skipped items.

## 5. Import the Transformed Output into OpenSearch

1. **Create and Configure Index in OpenSearch**:
   - Set up an index in OpenSearch to match the configuration of the index created in ElasticSearch.
   - Ensure the index is properly configured with the required mappings and settings.

2. **Import Test Data**:
   - Import the test dataset into OpenSearch to match the data in the ElasticSearch environment.

3. **Navigate to Saved Objects**:
   - Go to OpenSearch and navigate to **Management > Dashboard Management > Saved Objects**.

4. **Import the Sanitized File**:
   - Click on **Import** and select the sanitized file (`sanitized.ndjson`).
   - Follow the prompts to complete the import process.

## 6. Verify the Dashboard

1. **Verify Each Imported Dashboard**:
   - For each imported dashboard, ensure that it is displayed properly in OpenSearch.
   - Confirm that the dashboard has the same functionality as the original dashboard in ElasticSearch.
   - Verify that the dashboard provides the same information and data as the original.
   - Repeat this verification process for each and every dashboard to ensure consistency and accuracy.

## 7. Post-Test Activities

### 7.1. **Review Test Results**

- Analyze results for any discrepancies or issues encountered during the testing process.
- Investigate any inconsistencies between the source (ElasticSearch/Kibana) and target (OpenSearch) dashboards.

### 7.2. **Handle Issues**

- **Sanitization Issues**: If problems are identified during the sanitization step, investigate the cause and address any errors in the migration library or the sanitization process.
- **Importing Issues**: If issues occur during the import process, verify that the OpenSearch index configuration matches the original ElasticSearch setup and that the sanitized file is correctly formatted.
- **Verification Issues**: For discrepancies found during the verification of dashboards, ensure that the dashboards in OpenSearch match the original dashboards from ElasticSearch/Kibana in terms of display, functionality, and data.

### 7.3. **Create Unit Tests**

- If an issue arises that cannot be immediately resolved, create a dedicated unit test to isolate and verify the code changes related to the problem.
- Ensure that the unit test specifically targets the issue identified and helps in reproducing and fixing the problem.

### 7.4. **Simplify Test Data**

- **Simplification**: When creating or modifying test data, simplify it as much as possible to make it easier to understand and debug.
- **Focused Testing**: Use simplified test data to focus on specific features or issues. This can help in isolating problems and validating fixes more effectively.

## 8. Best Practices

- **Utilize ElasticSearch Migration Framework**:
  - ElasticSearch has a built-in framework for managing the migration of saved objects between versions. Leverage this framework to guide the creation of test cases and ensure that the migration library aligns with the established migration patterns.
  - Use the migration snippets provided by ElasticSearch as a foundation for defining test cases. These snippets represent common migration scenarios and can help ensure comprehensive testing of the migration process.

- **Apply Migration Scripts Based on Version**:
  - Ensure that migration scripts are applied according to the version of the data being imported. This means that each migration script should be executed if the imported version is older than the current version.
  - Verify that all necessary migration scripts are executed in sequence to accurately transform the data and saved objects from the older version to the new version.

## Conclusion

This run book provides a structured approach to verifying the functionality of the migration library. Following these steps will help ensure that ElasticSearch/Kibana dashboards are accurately and effectively transformed into OpenSearch equivalents.
