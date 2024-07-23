FROM amazonlinux:2023

ENV DEBIAN_FRONTEND noninteractive
ENV PIP_ROOT_USER_ACTION ignore
ENV LANG C.UTF-8

COPY Pipfile .
COPY Pipfile.lock .

RUN dnf install -y --setopt=install_weak_deps=False \
        python3.11 \
        python3.11-pip \
        python3.11-devel \
        python3.11-wheel \
        wget \
        gcc \
        git \
        curl-minimal \
        vim \
        jq \
        unzip \
        less \
        tar \
        glibc-devel && \
    dnf clean all && \
    rm -rf /var/cache/dnf

RUN pip3.11 install pipenv && \
    pipenv install --ignore-pipfile --deploy --python /usr/bin/python3.11 2>&1


COPY *.sh /root/
COPY *.py /root/

RUN ln -s /usr/bin/python3.11 /usr/bin/python

RUN chmod ug+x /root/*.sh
RUN chmod ug+x /root/*.py

CMD tail -f /dev/null
