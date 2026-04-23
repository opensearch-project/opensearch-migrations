# Global ARGs (must be before first FROM to be usable in FROM lines)
ARG CERT_BUILDER_IMAGE=docker.io/library/amazonlinux:2023
ARG RUNTIME_IMAGE=docker.io/library/amazoncorretto:11-al2023-headless

# =======================
# Stage 1: Certificate Generation
# =======================
FROM ${CERT_BUILDER_IMAGE} AS cert-builder

# Install OpenSSL
RUN yum update -y && \
    yum install -y openssl && \
    yum clean all

# Generate self-signed certificates
RUN mkdir -p /certs && \
    openssl req -x509 -nodes -newkey rsa:4096 \
    -keyout /certs/key.pem \
    -out /certs/ca.pem \
    -subj "/CN=localhost" \
    -days 36500 && \
    openssl req -new -key /certs/key.pem -out /certs/key.csr -subj "/CN=localhost" && \
    openssl x509 -req -in /certs/key.csr -CA /certs/ca.pem -CAkey /certs/key.pem -CAcreateserial -out /certs/pub.pem -days 36500 -sha256

# =======================
# Stage 2: Final Image
# =======================
FROM ${RUNTIME_IMAGE}

# Set environment variables
ENV CONFIG_HOME=/usr/share/captureProxy/config

# Copy certificates from builder stage
USER root
RUN mkdir -p $CONFIG_HOME/certs
COPY --from=cert-builder /certs/ca.pem $CONFIG_HOME/ca.pem
COPY --from=cert-builder /certs/pub.pem $CONFIG_HOME/pub.pem
COPY --from=cert-builder /certs/key.pem $CONFIG_HOME/key.pem
RUN chown -R root:root $CONFIG_HOME/

# Update the default Java truststore to trust the root-ca
RUN keytool -import -alias root-ca -cacerts -file $CONFIG_HOME/ca.pem -storepass changeit -noprompt

# Default port
EXPOSE 9200

# Set the entrypoint
CMD ["tail", "-f", "/dev/null"]