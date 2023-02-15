FROM docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2

COPY loggable-transport-netty4-7.10.2-SNAPSHOT.zip /root
RUN mkdir /httpTraceLogs ; chown elasticsearch httpTraceLogs
RUN /usr/share/elasticsearch/bin/elasticsearch-plugin install file:/root/loggable-transport-netty4-7.10.2-SNAPSHOT.zip
COPY elasticsearch.yml /usr/share/elasticsearch/config/
COPY log4j2.properties /usr/share/elasticsearch/config/

# would be nice to get this working to offload the files
#RUN sed -i -e "s|mirrorlist=|#mirrorlist=|g" /etc/yum.repos.d/CentOS-* ;  sed -i -e "s|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g" /etc/yum.repos.d/CentOS-*
#RUN yum install amazon-cloudwatch-agent

CMD /usr/local/bin/docker-entrypoint.sh eswrapper