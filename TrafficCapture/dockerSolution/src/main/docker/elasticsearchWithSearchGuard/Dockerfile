FROM custom-elasticsearch:7.10.2

RUN echo y | /usr/share/elasticsearch/bin/elasticsearch-plugin install https://maven.search-guard.com/search-guard-suite-release/com/floragunn/search-guard-suite-plugin/7.10.2-53.5.0/search-guard-suite-plugin-7.10.2-53.5.0.zip
# add search-guard, which provides TLS, as well as other features that we don't need to consider
RUN pushd /usr/share/elasticsearch/plugins/search-guard-7/tools ; chmod ugo+x ./install_demo_configuration.sh ; yes | ./install_demo_configuration.sh ; popd
ENV ELASTIC_SEARCH_CONFIG_FILE=/usr/share/elasticsearch/config/elasticsearch.yml
ENV PROXY_TLS_CONFIG_FILE=/usr/share/elasticsearch/config/proxy_tls.yml
ENV ELASTIC_SEARCH_JVM_CONFIG_FILE=/usr/share/elasticsearch/config/jvm.options

RUN bin/elasticsearch-plugin install --batch repository-s3
RUN  bin/elasticsearch-keystore create --silent && \
     echo "test" | bin/elasticsearch-keystore add --stdin s3.client.default.access_key && \
     echo "test" | bin/elasticsearch-keystore add --stdin s3.client.default.secret_key && \
     echo "test" | bin/elasticsearch-keystore add --stdin s3.client.default.session_token


# without this line, elasticsearch will complain that there aren't enough nodes
RUN echo "discovery.type: single-node" >> $ELASTIC_SEARCH_CONFIG_FILE
COPY disableTlsConfig.sh enableTlsConfig.sh /root/
RUN chmod ugo+x /root/disableTlsConfig.sh /root/enableTlsConfig.sh
ENV PATH=${PATH}:/usr/share/elasticsearch/jdk/bin/
RUN sed 's/searchguard/plugins.security/g' $ELASTIC_SEARCH_CONFIG_FILE |  \
    grep ssl.http > $PROXY_TLS_CONFIG_FILE

# The following two commands are more convenient for development purposes,
# but maybe not for a demo to show individual steps
RUN /root/enableTlsConfig.sh $ELASTIC_SEARCH_CONFIG_FILE
# Alter this config line to either enable(searchguard.disabled: false) or disable(searchguard.disabled: true) HTTP auth
RUN echo -n "searchguard.disabled: false" >> $ELASTIC_SEARCH_CONFIG_FILE  && \
    echo -n "plugins.security.ssl.http.enabled_protocols: ['TLSv1.2', 'TLSv1.3']" >> $PROXY_TLS_CONFIG_FILE

RUN sed -i '/^-Xms/i # Increase default heap to 80% RAM, Requires JDK >= 10' $ELASTIC_SEARCH_JVM_CONFIG_FILE && \
    sed -i 's/^-Xms/#&/' $ELASTIC_SEARCH_JVM_CONFIG_FILE && \
    sed -i 's/^-Xmx/#&/' $ELASTIC_SEARCH_JVM_CONFIG_FILE && \
    sed -i '/^#-Xmx/s/$/\n-XX:MaxRAMPercentage=80.0/' $ELASTIC_SEARCH_JVM_CONFIG_FILE


#CMD tail -f /dev/null
CMD []
