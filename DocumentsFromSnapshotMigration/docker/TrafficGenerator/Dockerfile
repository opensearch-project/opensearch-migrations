FROM migrations/elasticsearch_client_test_console:latest

COPY generateDataset.sh /root
RUN chmod ug+x /root/generateDataset.sh
ENTRYPOINT ["/root/generateDataset.sh"]