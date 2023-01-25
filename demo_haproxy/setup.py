import setuptools

setuptools.setup(
    name="demo_haproxy",
    version="0.1",
    description="Demoing HAProxy as a t-splitter",
    author="opensearch-migrations",
    # Collect all sub packages and place egg at parent dir level
    packages=setuptools.find_packages(where="demo_haproxy").append(""),
    install_requires=[
    ],
    python_requires=">=3.6",
)
