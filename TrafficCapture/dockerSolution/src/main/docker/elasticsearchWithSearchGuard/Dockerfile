FROM custom-elasticsearch:7.10.2

# Custom image runs as user 1000 on Alpine — need root for package install and file ops
USER root
RUN apk add --no-cache bash

# Switch to elasticsearch user for plugin installs
USER 1000

RUN echo y | /usr/share/elasticsearch/bin/elasticsearch-plugin install https://maven.search-guard.com/search-guard-suite-release/com/floragunn/search-guard-suite-plugin/7.10.2-53.5.0/search-guard-suite-plugin-7.10.2-53.5.0.zip
# add search-guard, which provides TLS, as well as other features that we don't need to consider
RUN chmod ugo+x /usr/share/elasticsearch/plugins/search-guard-7/tools/install_demo_configuration.sh && \
    yes | /usr/share/elasticsearch/plugins/search-guard-7/tools/install_demo_configuration.sh

ENV ELASTIC_SEARCH_CONFIG_FILE=/usr/share/elasticsearch/config/elasticsearch.yml
ENV PROXY_TLS_CONFIG_FILE=/usr/share/elasticsearch/config/proxy_tls.yml
ENV ELASTIC_SEARCH_JVM_CONFIG_FILE=/usr/share/elasticsearch/config/jvm.options

# Deduplicate YAML keys: Search Guard demo config duplicates settings from the custom image
# (cluster.name, network.host, discovery.type, etc). Keep first occurrence of each key.
# Also remove cluster.initial_master_nodes — conflicts with discovery.type=single-node
RUN awk -F: '/^[a-zA-Z]/ && NF>1 {if(seen[$1]++) next} 1' $ELASTIC_SEARCH_CONFIG_FILE > /tmp/es.yml && \
    mv /tmp/es.yml $ELASTIC_SEARCH_CONFIG_FILE && \
    sed -i '/^cluster\.initial_master_nodes/d' $ELASTIC_SEARCH_CONFIG_FILE

RUN bin/elasticsearch-plugin install --batch repository-s3
RUN  bin/elasticsearch-keystore create --silent && \
     echo "test" | bin/elasticsearch-keystore add --stdin s3.client.default.access_key && \
     echo "test" | bin/elasticsearch-keystore add --stdin s3.client.default.secret_key && \
     echo "test" | bin/elasticsearch-keystore add --stdin s3.client.default.session_token

# Copy TLS config scripts to accessible location
USER root
COPY disableTlsConfig.sh enableTlsConfig.sh /usr/local/bin/
RUN chmod ugo+x /usr/local/bin/disableTlsConfig.sh /usr/local/bin/enableTlsConfig.sh
USER 1000

ENV PATH=${PATH}:/usr/share/elasticsearch/jdk/bin/
RUN sed 's/searchguard/plugins.security/g' $ELASTIC_SEARCH_CONFIG_FILE |  \
    grep ssl.http > $PROXY_TLS_CONFIG_FILE

# The following two commands are more convenient for development purposes,
# but maybe not for a demo to show individual steps
RUN /usr/local/bin/enableTlsConfig.sh $ELASTIC_SEARCH_CONFIG_FILE
# Alter this config line to either enable(searchguard.disabled: false) or disable(searchguard.disabled: true) HTTP auth
RUN echo -n "searchguard.disabled: false" >> $ELASTIC_SEARCH_CONFIG_FILE  && \
    echo -n "plugins.security.ssl.http.enabled_protocols: ['TLSv1.2', 'TLSv1.3']" >> $PROXY_TLS_CONFIG_FILE

RUN sed -i '/^-Xms/i # Increase default heap to 80% RAM, Requires JDK >= 10' $ELASTIC_SEARCH_JVM_CONFIG_FILE && \
    sed -i 's/^-Xms/#&/' $ELASTIC_SEARCH_JVM_CONFIG_FILE && \
    sed -i 's/^-Xmx/#&/' $ELASTIC_SEARCH_JVM_CONFIG_FILE && \
    sed -i '/^#-Xmx/s/$/\n-XX:MaxRAMPercentage=80.0/' $ELASTIC_SEARCH_JVM_CONFIG_FILE

# Override healthcheck for HTTPS (SearchGuard enables TLS)
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
  CMD curl -sf --insecure https://localhost:9200/_cluster/health || exit 1

CMD []
