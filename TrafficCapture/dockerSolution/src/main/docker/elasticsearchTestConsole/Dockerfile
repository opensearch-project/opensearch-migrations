FROM ubuntu:jammy

ENV DEBIAN_FRONTEND noninteractive
ENV PIP_ROOT_USER_ACTION ignore

COPY Pipfile .
COPY Pipfile.lock .

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3.10 python3-pip python3-dev openjdk-11-jre-headless wget gcc libc-dev git curl vim jq unzip less && \
    pip install pipenv && \
    pipenv install --ignore-pipfile --deploy

COPY *.sh /root/
COPY *.py /root/

RUN ln -s /usr/bin/python3 /usr/bin/python

RUN chmod ug+x /root/*.sh
RUN chmod ug+x /root/*.py

CMD tail -f /dev/null
