# =======================
# Stage 1: Certificate Generation
# =======================
FROM mirror.gcr.io/library/amazonlinux:2023 AS cert-builder

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
FROM mirror.gcr.io/library/amazoncorretto:11-al2023-headless

# Set environment variables
ENV CONFIG_HOME=/usr/share/captureProxy/config
ENV PROXY_TLS_CONFIG_FILE=$CONFIG_HOME/proxy_tls.yml

# Copy certificates from builder stage
USER root
RUN mkdir -p $CONFIG_HOME/certs
COPY --from=cert-builder /certs/ca.pem $CONFIG_HOME/ca.pem
COPY --from=cert-builder /certs/pub.pem $CONFIG_HOME/pub.pem
COPY --from=cert-builder /certs/key.pem $CONFIG_HOME/key.pem
RUN chown -R root:root $CONFIG_HOME/

# Update the default Java truststore to trust the root-ca
RUN keytool -import -alias root-ca -cacerts -file $CONFIG_HOME/ca.pem -storepass changeit -noprompt

# Note: The following configurations are added to ensure compatibility with the es-7.10-oss base image.
# The proxy_tls.yml file are configured to work seamlessly with the Capture Proxy.
RUN echo 'plugins.security.ssl.http.enabled: true' >> $PROXY_TLS_CONFIG_FILE && \
    echo 'plugins.security.ssl.http.pemcert_filepath: pub.pem' >> $PROXY_TLS_CONFIG_FILE && \
    echo 'plugins.security.ssl.http.pemkey_filepath: key.pem' >> $PROXY_TLS_CONFIG_FILE && \
    echo 'plugins.security.ssl.http.pemtrustedcas_filepath: ca.pem' >> $PROXY_TLS_CONFIG_FILE && \
    echo "plugins.security.ssl.http.enabled_protocols: ['TLSv1.2', 'TLSv1.3']" >> $PROXY_TLS_CONFIG_FILE

RUN chmod 644 $PROXY_TLS_CONFIG_FILE && \
    chown root:root $PROXY_TLS_CONFIG_FILE

# Default port
EXPOSE 9200

# Set the entrypoint
CMD ["tail", "-f", "/dev/null"]