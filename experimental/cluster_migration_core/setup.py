import setuptools

setuptools.setup(
    name="cluster_migration_core",
    version="0.1",
    description="Core library to assist testing migrations/upgrades between versions of ElasticSearch and OpenSearch",
    author="opensearch-migrations",
    # Collect all sub packages and place egg at parent dir level
    packages=setuptools.find_packages(where="cluster_migration_core").append(""),
    install_requires=[
        "coloredlogs",
        "docker",
        "pexpect",
        "pytest>=7.4.2",
        "requests",
        "robotframework"
    ],
    python_requires=">=3.6",
)
