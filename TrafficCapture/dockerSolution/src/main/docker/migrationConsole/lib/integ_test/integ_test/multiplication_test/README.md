# Document Multiplication Test

## Assumptions

- CDK deploy has been performed successfully for MA v2.5 and greater
- RFS service is enabled with transformation support
- Access to the migration-console container
- Source cluster == target cluster (only source cluster needed)
- Source cluster is connected and accessible

## Prerequisites

### Required AWS Resources

- **S3 Bucket**: Final snapshot bucket must exist and be accessible
- **IAM Role**: Role with permissions to access the S3 bucket must be present in the same AWS account

### Verification Commands

```bash
# Verify configuration
cat /config/migration_services.yaml

# Test cluster connectivity
console clusters connection-check
```

## Where to Run

Execute these tests from **inside the migration-console container**:

```bash
# Access the migration console
./accessContainer.sh migration-console <STAGE> <REGION>
```

## How to Run

Run the following commands **sequentially** from within the migration-console:

```bash
# Navigate to test directory
cd /root/lib/integ_test/

# Part 1: Clean up and prepare environment
python -m integ_test.multiplication_test.CleanUpAndPrepare

# Part 2: Run RFS to multiply documents
python -m integ_test.multiplication_test.MultiplyDocuments

# Part 3: Create final snapshot
python -m integ_test.multiplication_test.CreateFinalSnapshot
```

**Note**: These modules must be run in order. Do not run them in parallel.
