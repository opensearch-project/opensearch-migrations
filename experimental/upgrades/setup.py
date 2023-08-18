import setuptools

setuptools.setup(
    name="upgrade_testing_framework",
    version="0.1",
    description="Tooling to help test upgrades between versions of ElasticSearch and OpenSearch",
    author="OpenSearch Migrations",
    package_dir={"": "upgrade_testing_framework"},
    packages=setuptools.find_packages(where="upgrade_testing_framework"),
    package_data={'': ['*.robot']},
    install_requires=[
        "opensearch-py"
    ],
    python_requires=">=3.6",
)
