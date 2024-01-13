FROM docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2

RUN echo y | /usr/share/elasticsearch/bin/elasticsearch-plugin install https://maven.search-guard.com/search-guard-suite-release/com/floragunn/search-guard-suite-plugin/7.10.2-53.5.0/search-guard-suite-plugin-7.10.2-53.5.0.zip
# add search-guard, which provides TLS, as well as other features that we don't need to consider
RUN pushd /usr/share/elasticsearch/plugins/search-guard-7/tools ; chmod ugo+x ./install_demo_configuration.sh ; yes | ./install_demo_configuration.sh ; popd
ENV ELASTIC_SEARCH_CONFIG_FILE=/usr/share/elasticsearch/config/elasticsearch.yml
ENV PROXY_TLS_CONFIG_FILE=/usr/share/elasticsearch/config/proxy_tls.yml
# without this line, elasticsearch will complain that there aren't enough nodes
RUN echo "discovery.type: single-node" >> $ELASTIC_SEARCH_CONFIG_FILE
COPY ./TrafficCapture/dockerSolution/src/main/docker/elasticsearchWithSearchGuard/disableTlsConfig.sh /root/
COPY ./TrafficCapture/dockerSolution/src/main/docker/elasticsearchWithSearchGuard/enableTlsConfig.sh /root/
RUN chmod ugo+x /root/disableTlsConfig.sh /root/enableTlsConfig.sh
ENV PATH=${PATH}:/usr/share/elasticsearch/jdk/bin/
RUN sed 's/searchguard/plugins.security/g' $ELASTIC_SEARCH_CONFIG_FILE |  \
    grep ssl.http > $PROXY_TLS_CONFIG_FILE

# The following two commands are more convenient for development purposes,
# but maybe not for a demo to show individual steps
RUN /root/enableTlsConfig.sh $ELASTIC_SEARCH_CONFIG_FILE
# Alter this config line to either enable(searchguard.disabled: false) or disable(searchguard.disabled: true) HTTP auth
RUN echo "searchguard.disabled: false" >> $ELASTIC_SEARCH_CONFIG_FILE

#CMD tail -f /dev/null
#CMD /usr/local/bin/docker-entrypoint.sh eswrapper



RUN sed -i -e "s|mirrorlist=|#mirrorlist=|g" /etc/yum.repos.d/CentOS-* ;  sed -i -e "s|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g" /etc/yum.repos.d/CentOS-*
RUN yum -y install nmap-ncat
COPY ./TrafficCapture/dockerSolution/build/docker/trafficCaptureProxyServer/jars /jars
RUN printf "#!/bin/sh\njava -cp `echo /jars/*.jar | tr \   :` \"\$@\" " > /runJavaWithClasspath.sh
RUN chmod +x /runJavaWithClasspath.sh
CMD ["tail", "-f", "/dev/null"]