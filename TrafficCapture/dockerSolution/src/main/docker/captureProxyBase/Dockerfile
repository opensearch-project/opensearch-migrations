# =======================
# Stage 1: Certificate Generation
# =======================
FROM amazonlinux:2023 AS cert-builder

# Install OpenSSL
RUN yum update -y && \
    yum install -y openssl && \
    yum clean all

# Generate self-signed certificates
RUN mkdir -p /certs && \
    openssl req -x509 -nodes -newkey rsa:4096 \
    -keyout /certs/esnode-key.pem \
    -out /certs/root-ca.pem \
    -subj "/CN=localhost" \
    -days 365 && \
    openssl req -new -key /certs/esnode-key.pem -out /certs/esnode.csr -subj "/CN=localhost" && \
    openssl x509 -req -in /certs/esnode.csr -CA /certs/root-ca.pem -CAkey /certs/esnode-key.pem -CAcreateserial -out /certs/esnode.pem -days 365 -sha256

# =======================
# Stage 2: Final Image
# =======================
FROM amazoncorretto:11-al2023-headless

# Set environment variables
ENV ES_HOME=/usr/share/elasticsearch
RUN mkdir -p $ES_HOME
ENV PATH=$ES_HOME/bin:$PATH
ENV ELASTIC_SEARCH_CONFIG_FILE=$ES_HOME/config/elasticsearch.yml
ENV PROXY_TLS_CONFIG_FILE=$ES_HOME/config/proxy_tls.yml

# Copy certificates from builder stage
USER root
RUN mkdir -p $ES_HOME/certs
COPY --from=cert-builder /certs/root-ca.pem $ES_HOME/config/root-ca.pem
COPY --from=cert-builder /certs/esnode.pem $ES_HOME/config/esnode.pem
COPY --from=cert-builder /certs/esnode-key.pem $ES_HOME/config/esnode-key.pem
RUN chown -R root:root $ES_HOME/

# Update the default Java truststore to trust the root-ca
RUN keytool -import -alias root-ca -cacerts -file $ES_HOME/config/root-ca.pem -storepass changeit -noprompt
# Set environment variables for Search Guard
ENV SEARCHGUARD_CONFIG_DIR=$ES_HOME/config
ENV SEARCHGUARD_CERTS_DIR=$ES_HOME/config

# Note: The following configurations are added to ensure compatibility with the es-7.10-oss base image used in the Dockerfile.
# The elasticsearch.yml and proxy_tls.yml files are configured to work seamlessly with the Search Guard plugin and TLS settings.

# Add base elasticsearch.yml content
RUN echo 'searchguard.ssl.transport.pemcert_filepath: esnode.pem' >> $ELASTIC_SEARCH_CONFIG_FILE && \
    echo 'searchguard.ssl.transport.pemkey_filepath: esnode-key.pem' >> $ELASTIC_SEARCH_CONFIG_FILE && \
    echo 'searchguard.ssl.transport.pemtrustedcas_filepath: root-ca.pem' >> $ELASTIC_SEARCH_CONFIG_FILE && \
    echo 'searchguard.ssl.transport.enforce_hostname_verification: false' >> $ELASTIC_SEARCH_CONFIG_FILE && \
    echo 'searchguard.ssl.http.enabled: true' >> $ELASTIC_SEARCH_CONFIG_FILE && \
    echo 'searchguard.ssl.http.pemcert_filepath: esnode.pem' >> $ELASTIC_SEARCH_CONFIG_FILE && \
    echo 'searchguard.ssl.http.pemkey_filepath: esnode-key.pem' >> $ELASTIC_SEARCH_CONFIG_FILE && \
    echo 'searchguard.ssl.http.pemtrustedcas_filepath: root-ca.pem' >> $ELASTIC_SEARCH_CONFIG_FILE

# Add base proxy_tls.yml content
RUN echo 'plugins.security.ssl.http.enabled: true' >> $PROXY_TLS_CONFIG_FILE && \
    echo 'plugins.security.ssl.http.pemcert_filepath: esnode.pem' >> $PROXY_TLS_CONFIG_FILE && \
    echo 'plugins.security.ssl.http.pemkey_filepath: esnode-key.pem' >> $PROXY_TLS_CONFIG_FILE && \
    echo 'plugins.security.ssl.http.pemtrustedcas_filepath: root-ca.pem' >> $PROXY_TLS_CONFIG_FILE && \
    echo "plugins.security.ssl.http.enabled_protocols: ['TLSv1.2', 'TLSv1.3']" >> $PROXY_TLS_CONFIG_FILE


# Set the right permissions and ownership for created files
RUN chmod 644 $ELASTIC_SEARCH_CONFIG_FILE && \
    chmod 644 $PROXY_TLS_CONFIG_FILE && \
    chown root:root $ELASTIC_SEARCH_CONFIG_FILE && \
    chown root:root $PROXY_TLS_CONFIG_FILE

# Expose ports
EXPOSE 9200

# Set the entrypoint
CMD ["tail", "-f", "/dev/null"]