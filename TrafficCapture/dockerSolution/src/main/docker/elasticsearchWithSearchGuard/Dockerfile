FROM docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2

RUN echo y | /usr/share/elasticsearch/bin/elasticsearch-plugin install https://maven.search-guard.com/search-guard-suite-release/com/floragunn/search-guard-suite-plugin/7.10.2-53.5.0/search-guard-suite-plugin-7.10.2-53.5.0.zip
# add search-guard, which provides TLS, as well as other features that we don't need to consider
RUN pushd /usr/share/elasticsearch/plugins/search-guard-7/tools ; chmod ugo+x ./install_demo_configuration.sh ; yes | ./install_demo_configuration.sh ; popd
ENV configFile=/usr/share/elasticsearch/config/elasticsearch.yml
# without this line, elasticsearch will complain that there aren't enough nodes
RUN echo "discovery.type: single-node" >> $configFile
COPY disableTlsConfig.sh enableTlsConfig.sh /root/
RUN chmod ugo+x /root/disableTlsConfig.sh /root/enableTlsConfig.sh
ENV PATH=${PATH}:/usr/share/elasticsearch/jdk/bin/

# The following two commands are more convenient for development purposes,
# but maybe not for a demo to show individual steps
RUN /root/disableTlsConfig.sh $configFile
# Do this to disable HTTP auth
RUN echo "searchguard.disabled: true" >> $configFile

#CMD tail -f /dev/null
CMD /usr/local/bin/docker-entrypoint.sh eswrapper