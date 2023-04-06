# Important things that still need to be done


1. Fiil out the rest of the Serialization to protobufs 
   1. partial reads/writes, all writes, anything else deemed to be useful
2. Refactoring the serialization models (generated protobufs) into a separate library so that the consumers can use them.
3. The Offloader API that netty calls (& does the serialization)
4. An offloader implementation using Kafka and the serialization code that’s in place - trying to minimize memory operations
5. Netty changes to have HTTP awareness of the traffic to add some new markers and to block downstream writes until some of the offloader tasks have been confirmed to have been committed.
6. Confirm that the netty handlers are never blocking the pipelines
   1. Blocking a connection is fine - blocking the activity in the event loop group is NOT.
7. Stress test.
8. Optimize (reduce allocations via pooling and reuse?)