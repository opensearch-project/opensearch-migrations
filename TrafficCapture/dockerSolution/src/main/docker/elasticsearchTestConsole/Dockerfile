FROM amazonlinux:2023

ENV PIP_ROOT_USER_ACTION ignore
ENV LANG C.UTF-8

# Install HDF5 from source for Opensearch Benchmark compatibility with ARM
ENV HDF5_VERSION 1.14.4
RUN dnf install -y --setopt=install_weak_deps=False \
      curl-minimal \
      tar \
      gzip \
      clang \
      glibc-devel \
      zlib-devel \
      && \
    dnf clean all && \
    rm -rf /var/cache/dnf

RUN mkdir /tmp/hdf5 && \
    curl -L https://github.com/HDFGroup/hdf5/archive/refs/tags/hdf5_${HDF5_VERSION}.tar.gz \
     | tar -xz -C /tmp/hdf5 --strip-components=1

ENV CC clang
ENV CXX clang++
ENV CFLAGS  "-w"
WORKDIR /tmp/hdf5
RUN ./configure --prefix=/usr/local \
      --disable-tests  \
      --disable-tools  \
      --disable-fortran \
      --disable-static \
      && \
    make -j$(nproc) && \
    make install && \
    rm -rf /tmp/hdf5
ENV HDF5_DIR=/usr/local

# procps-ng used for enabling 'watch' command on console
RUN dnf install -y --setopt=install_weak_deps=False \
        diffutils \
        git \
        hostname \
        java-17-amazon-corretto \
        jq \
        less \
        make \
        openssl-devel \
        pkg-config \
        procps-ng \
        python3.11 \
        python3.11-devel \
        python3.11-pip \
        python3.11-wheel \
        unzip \
        vim \
        wget \
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
RUN python3.11 -m venv .venv

WORKDIR /root
COPY Pipfile .
COPY Pipfile.lock .
RUN pipenv install --deploy

COPY *.sh /root/
COPY *.py /root/

RUN chmod ug+x /root/*.sh
RUN chmod ug+x /root/*.py
RUN ln -s  `which vim` /usr/local/bin/vi

CMD tail -f /dev/null
