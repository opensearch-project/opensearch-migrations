import setuptools

setuptools.setup(
    name="robottests",
    version="0.1",
    description="Robot framework to simplify writing upgrade tests",
    author="opensearch-migrations",
    # Collect all sub packages and place egg at parent dir level
    packages=setuptools.find_packages(where="robot_tests").append(""),
    package_data={'': ['*.robot']},
    include_package_data=True,
    install_requires=[
        "robotframework",
        "docutils",
        "opensearch-py"
    ],
    python_requires=">=3.6",
)