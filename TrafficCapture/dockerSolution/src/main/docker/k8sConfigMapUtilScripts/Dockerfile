FROM amazonlinux:2023

ENV PIP_ROOT_USER_ACTION=ignore
ENV LANG=C.UTF-8

RUN dnf install -y \
        jq \
        less \
        python3.11 \
        python3.11-devel \
        python3.11-pip \
        python3.11-wheel \
        tar \
        unzip \
        vim \
        wget \
        && \
    dnf clean all

# Define the virtual environment path to use for all pipenv runs
ENV WORKON_HOME=/
ENV PIPENV_CUSTOM_VENV_NAME=.venv
ENV PIPENV_DEFAULT_PYTHON_VERSION=3.11
ENV PIPENV_MAX_DEPTH=1

RUN python3.11 -m pip install pipenv
WORKDIR /
RUN python3.11 -m venv .venv

WORKDIR /root
COPY Pipfile .
COPY Pipfile.lock .
RUN pipenv install --deploy

COPY configmap2yaml /root/configmap2yaml/
RUN chmod ug+x /root/configmap2yaml/*.py

ENTRYPOINT ["tail", "-f", "/dev/null"]
