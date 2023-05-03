### Deployment
This directory is aimed at housing deployment/distribution methods for various migration related images and infrastructure. It is not specific to any given platform and should be expanded to more platforms as needed. 

It is worth noting that there is not a hard divide between these subdirectories and deployments such as [opensearch-service-migration](./cdk/opensearch-service-migration) will use Dockerfiles in the [docker](./docker) directory for some of its container deployments.


#### TODO Improvements
* Currently some of our Dockerfiles such as the [traffic-replayer]("./docker/traffic-replayer/Dockerfile") Dockerfile are still pulling from this public repository to get the relevant codebase for which to create an image. This will ignore any local changes that are not committed on the pulled repo. It would be nice to be able to easily build this image with local changes included. This can get a bit tricky to manage, though, as Docker has a concept of a build context which will limit the files available to access from the Dockerfile to the current directory. This could potentially be managed with a build script which would handle using any local directory, but there is also the usage in the [opensearch-service-migration](./cdk/opensearch-service-migration) which only accepts a Dockerfile location to be considered as well. 


* Create `docker-compose` files to mimic the existing [CDK](./cdk/opensearch-service-migration) infrastructure for Docker use cases