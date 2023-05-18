FROM docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2

RUN echo y | /usr/share/elasticsearch/bin/elasticsearch-plugin install https://maven.search-guard.com/search-guard-suite-release/com/floragunn/search-guard-suite-plugin/7.10.2-53.5.0/search-guard-suite-plugin-7.10.2-53.5.0.zip
RUN cd /usr/share/elasticsearch/plugins/search-guard-7/tools ; chmod ugo+x ./install_demo_configuration.sh ; yes | ./install_demo_configuration.sh
ENV configFile=/usr/share/elasticsearch/config/elasticsearch.yml
RUN echo "discovery.type: single-node" >> configFile

CMD tail -f /dev/null
#CMD /usr/local/bin/docker-entrypoint.sh eswrapper