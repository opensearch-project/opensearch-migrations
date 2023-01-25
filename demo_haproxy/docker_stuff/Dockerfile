FROM haproxytech/haproxy-ubuntu:2.8

# Ensure we have the latest package listing (doesn't inherit from base image's invocation)
RUN apt-get update

# Install for convenience during development; probably shouldn't be in published image
RUN apt-get install -y --no-install-recommends vim

# Install/Configure syslog.  Need a more sophisticated logging facility than just "Cloud Native" stdout/stderr.
RUN apt-get install -y --no-install-recommends rsyslog
COPY rsyslog.conf /etc/rsyslog.d/haproxy.conf

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD service rsyslog start && haproxy -f /usr/local/etc/haproxy/haproxy.cfg