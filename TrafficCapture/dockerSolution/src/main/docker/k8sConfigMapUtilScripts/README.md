```mermaid
graph 
   Console -->  SharedConfigs
   Proxy[Proxy Chart] --> SharedConfigs
   Proxy --> singleUseConfigs[Direct Single Use ConfigsListenPort]  
   Replayer[Replayer Chart] --> | c | SharedConfigs
   SharedConfigs --> Kafka
   Kafka --> KafkaExample[kafkaBroker:\n..brokerEndpoints: kafka-cluster-kafka-bootstrap:9092\n..auth: aws ]
   style KafkaExample text-align:left
   SharedConfigs --> SourceCluster
   SharedConfigs --> OtelEndpoint
   RegistrySidecar --> SharedConfigs
   subgraph SharedConfigs[Global Configs Chart]
       subgraph ConfigMaps 
           SourceCluster
           OtelEndpoint
           Kafka
       end
   end
   Console[Migration Console] --> else 
```

.......................................................................................................................................................................................................................,,ffdf