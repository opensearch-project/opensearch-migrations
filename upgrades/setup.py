import setuptools

setuptools.setup(
    name="upgrade_testing_framework",
    version="0.1",
    description="Tools and scripts to help test upgrades between versions of ElasticSearch and OpenSearch",
    author="Chris Helma",
    package_dir={"": "upgrade_testing_framework"},
    packages=setuptools.find_packages(where="upgrade_testing_framework"),
    install_requires=[
        "coloredlogs",
        "docker",
        "pexpect",
        "py",
        "pytest",
        "requests"
    ],
    python_requires=">=3.6",
)