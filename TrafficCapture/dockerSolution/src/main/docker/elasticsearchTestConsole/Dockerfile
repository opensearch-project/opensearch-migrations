FROM amazonlinux:2023

ENV PIP_ROOT_USER_ACTION ignore
ENV LANG C.UTF-8

RUN dnf install -y --setopt=install_weak_deps=False \
        curl-minimal \
        diffutils \
        gcc \
        gcc-c++\
        git \
        glibc-devel \
        hostname \
        java-11-amazon-corretto \
        jq \
        less \
        make \
        openssl-devel \
        pkg-config \
        python3.11 \
        python3.11-devel \
        python3.11-pip \
        python3.11-wheel \
        tar \
        unzip \
        vim \
        wget \
        zlib-devel \
        && \
    dnf clean all && \
    rm -rf /var/cache/dnf

# Install HDF5 from source for Opensearch Benchmark compatibility with ARM
ARG HDF5_VERSION=1.14.4
RUN wget --tries=5 --retry-connrefused --waitretry=15 -q https://github.com/HDFGroup/hdf5/archive/refs/tags/hdf5_${HDF5_VERSION}.tar.gz -O /tmp/hdf5.tar.gz && \
    mkdir /tmp/hdf5 && \
    tar -xzf /tmp/hdf5.tar.gz -C /tmp/hdf5 --strip-components=1 && \
    rm /tmp/hdf5.tar.gz
WORKDIR /tmp/hdf5
RUN CFLAGS="-w" ./configure --prefix=/usr/local --disable-tests --disable-tools && \
    make -j$(nproc) && \
    make install -j$(nproc) && \
    rm -rf /tmp/hdf5

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
RUN HDF5_DIR=/usr/local pipenv install --deploy

COPY *.sh /root/
COPY *.py /root/

RUN chmod ug+x /root/*.sh
RUN chmod ug+x /root/*.py

CMD tail -f /dev/null
