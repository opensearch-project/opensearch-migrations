# ---------- 1) Builder ----------
FROM amazonlinux:2023 AS builder

# Build deps (toolchain + headers)
RUN dnf install -y \
      --setopt=install_weak_deps=False \
      --setopt=tsflags=nodocs \
      --setopt=keepcache=False \
      --setopt=plugins=0 \
      --setopt=logfilelevel=0 \
      gcc \
      libffi-devel \
      make \
      openssl-devel \
      pkg-config \
      python3.11 \
      python3.11-devel \
      python3.11-pip \
      python3.11-wheel \
  && dnf clean all && rm -rf /var/cache/dnf

ENV PIP_ROOT_USER_ACTION ignore
ENV LANG C.UTF-8

# Define the virtual environment path to use for all pipenv runs
ENV WORKON_HOME=/
ENV PIPENV_CUSTOM_VENV_NAME=.venv
ENV PIPENV_DEFAULT_PYTHON_VERSION=3.11
ENV PIPENV_MAX_DEPTH=1

# Pipenv & project install into a project-local venv we can copy out
RUN python3.11 -m pip install pipenv
WORKDIR /
RUN python3.11 -m venv .venv
COPY Pipfile Pipfile.lock ./
RUN pipenv install --deploy

# ---------- 2) Runtime ----------
FROM amazonlinux:2023

ENV PIP_ROOT_USER_ACTION ignore
ENV LANG C.UTF-8

# Minimal runtime deps (no compilers, no *-devel)
RUN dnf install -y \
      --setopt=install_weak_deps=False \
      --setopt=tsflags=nodocs \
      --setopt=keepcache=False \
      --setopt=plugins=0 \
      --setopt=logfilelevel=0 \
        curl-minimal \
        diffutils \
        git \
        gzip \
        hostname \
        java-17-amazon-corretto-headless \
        jq \
        less \
        make \
        openssl \
        pkg-config \
        procps-ng \
        python3.11 \
        python3.11-pip \
        tar \
        unzip \
        vim \
        which \
        wget \
        zlib \
        && \
    dnf clean all && \
    rm -rf /var/cache/dnf

# Define the virtual environment path to use for all pipenv runs
ENV WORKON_HOME=/
ENV PIPENV_CUSTOM_VENV_NAME=.venv
ENV PIPENV_DEFAULT_PYTHON_VERSION=3.11
ENV PIPENV_MAX_DEPTH=1

RUN python3.11 -m pip install pipenv
WORKDIR /
COPY --from=builder /.venv /.venv

# Copy the app and the built virtualenv from builder
WORKDIR /root
COPY Pipfile .
COPY Pipfile.lock .
# Already built from builder image, no build necessary

COPY *.sh /root/
COPY *.py /root/

RUN chmod ug+x /root/*.sh
RUN chmod ug+x /root/*.py
RUN ln -s $(command -v vim) /usr/local/bin/vi

CMD tail -f /dev/null
