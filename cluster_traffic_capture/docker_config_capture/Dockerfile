FROM haproxytech/haproxy-ubuntu:2.7 AS haproxy-base

# Ensure we have the latest package listing (doesn't inherit from base image's invocation)
RUN apt-get update

# Install for convenience during development; probably shouldn't be in published image
RUN apt-get install -y --no-install-recommends vim

# Install/Configure syslog.  Need a more sophisticated logging facility than just "Cloud Native" stdout/stderr.
RUN apt-get install -y --no-install-recommends rsyslog
# Set the max line length to 64k characters.
# This can't be in the rsyslog.conf, it needs to be a the beginning of the main conf file.
RUN sed -i '1s/^/$MaxMessageSize 64k\n/' /etc/rsyslog.conf
COPY rsyslog.conf /etc/rsyslog.d/haproxy.conf

# ====== Leverage Multi-Stage Docker Builds to re-use the base HAProxy image ======
FROM haproxy-base AS haproxy-no-mirror

# Copy our configuration over
COPY haproxy_no_mirror.cfg /usr/local/etc/haproxy/haproxy.cfg

CMD ["/bin/bash", "-c", "service rsyslog start && haproxy -f /usr/local/etc/haproxy/haproxy.cfg"]

# ====== Leverage Multi-Stage Docker Builds to re-use the base HAProxy image ======
FROM haproxy-base AS haproxy-w-mirror

# Install the traffic mirroring SPOE Agent
# See: https://www.haproxy.com/blog/haproxy-traffic-mirroring-for-real-world-testing/
RUN apt install -y autoconf automake build-essential git libcurl4-openssl-dev libev-dev libpthread-stubs0-dev pkg-config
RUN git clone https://github.com/haproxytech/spoa-mirror
RUN cd spoa-mirror && \
    ./scripts/bootstrap && \
    ./configure && \
    make all && \
    cp ./src/spoa-mirror /usr/local/bin/

# Copy our configuration over.  Do this after SPOA installation to prevent rebuilding that layer when config changes.
COPY mirror.conf /usr/local/etc/haproxy/mirror.conf
COPY haproxy_w_mirror.cfg /usr/local/etc/haproxy/haproxy.cfg

CMD ["/bin/bash", "-c", "service rsyslog start && haproxy -f /usr/local/etc/haproxy/haproxy.cfg"]

# ====== Create an AWS-Specific implementation layer for offload of captured traffic ======
FROM haproxy-w-mirror AS haproxy-w-mirror-aws

# Install the CloudWatch Agent, which is used for offload of captured traffic to CloudWatch Logs
ENV RUN_IN_CONTAINER=True
RUN apt-get install -y wget \
    && wget https://s3.amazonaws.com/amazoncloudwatch-agent/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb \
    && dpkg -i -E ./amazon-cloudwatch-agent.deb
COPY amazon-cloudwatch-agent.json /opt/aws/amazon-cloudwatch-agent/bin/default_linux_config.json

# Kick off rsyslog, CloudWatch Agent as a background process, and HAProxy.  All three should be managed by HAProxy
# using the "program" directive in the config file but that's a follow-up item
CMD ["/bin/bash", "-c", "service rsyslog start && /opt/aws/amazon-cloudwatch-agent/bin/start-amazon-cloudwatch-agent & && haproxy -f /usr/local/etc/haproxy/haproxy.cfg"]
