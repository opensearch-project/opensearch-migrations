## OpenSearch Migrations

```
Data flows to OpenSearch
On wings of the morning breeze
Effortless migration
```

This repo will contain code and documentation to assist in migrations and upgrades of OpenSearch clusters.

## Setup for commits

Developers must run the "install_githooks.sh" script in order to add the pre-commit hook.

## End-to-End Testing

Developers can run a test script which will verify the end-to-end solution.

To run the test script, users must navigate to the test directory, install the required packages then run the script:

```
cd test
pip install -r requirements.txt
pytest tests.py
```

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.

