from setuptools import setup, find_packages  # ignore: type

setup(
    name="console_link",
    version="1.0.0",
    description="A Python module to create a console application from a Python script",
    packages=find_packages(exclude=("tests")),
    install_requires=["requests", "boto3", "pyyaml", "Click", "cerberus",
                      "kubernetes", "rich>=14.0.0", "ruamel.yaml", "pydantic", "awscli"],
    entry_points={
        "console_scripts": [
            "console = console_link.cli:main",
            "workflow = console_link.workflow.cli:main",
        ],
    },
    classifiers=[
        "Development Status :: 3 - Alpha",
        "Intended Audience :: Developers",
        "Topic :: Software Development :: Build Tools",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.11",
        "License :: OSI Approved :: Apache Software License",
    ],
)
