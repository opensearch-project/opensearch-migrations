from setuptools import setup, find_packages  # ignore: type

setup(
    name="cluster_curl",
    version="1.0.0",
    description="A Python tool to make API calls against congfigured clusters",
    packages=find_packages(exclude=("tests")),
    install_requires=["console_link", "Click"],
    entry_points={
        "console_scripts": [
            "ccurl = cli:cli",
            "cluster-curl = cli:cli",
        ],
    },
    classifiers=[
        "Development Status :: 3 - Alpha",
        "Intended Audience :: Developers",
        "Topic :: Software Development :: Build Tools",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.6",
        "Programming Language :: Python :: 3.7",
        "Programming Language :: Python :: 3.8",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3.10",
        "License :: OSI Approved :: Apache Software License",
    ],
)
