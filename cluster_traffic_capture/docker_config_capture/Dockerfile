FROM haproxytech/haproxy-ubuntu:2.8 AS haproxy-base

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

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD service rsyslog start && haproxy -f /usr/local/etc/haproxy/haproxy.cfg

# ====== Leverage Multi-Stage Docker Builds to re-use the base HAProxy image ======
FROM haproxy-base AS haproxy-w-mirror

# Copy our configuration over
COPY haproxy_w_mirror.cfg /usr/local/etc/haproxy/haproxy.cfg
COPY mirror.conf /usr/local/etc/haproxy/mirror.conf

# Install the traffic mirroring SPOE Agent
# See: https://www.haproxy.com/blog/haproxy-traffic-mirroring-for-real-world-testing/
RUN apt install -y autoconf automake build-essential git libcurl4-openssl-dev libev-dev libpthread-stubs0-dev pkg-config
RUN git clone https://github.com/haproxytech/spoa-mirror
RUN cd spoa-mirror && \
    ./scripts/bootstrap && \
    ./configure && \
    make all && \
    cp ./src/spoa-mirror /usr/local/bin/

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD service rsyslog start && haproxy -f /usr/local/etc/haproxy/haproxy.cfg