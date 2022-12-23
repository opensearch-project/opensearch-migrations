import setuptools

setuptools.setup(
    name="upgrade_testing_clients",
    version="0.1",
    description="",
    author="opensearch-migrations",
    install_requires=[
        "pytest",
        "requests"
    ],
    python_requires=">=3.6",
)