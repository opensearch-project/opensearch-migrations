FROM openjdk:11-jre
RUN apt-get update && apt-get install -y netcat
#COPY jars /jars
COPY ./TrafficCapture/dockerSolution/build/docker/trafficReplayer/jars /jars
RUN printf "#!/bin/sh\njava -cp `echo /jars/*.jar | tr \   :` \"\$@\" " > /runJavaWithClasspath.sh
RUN chmod +x /runJavaWithClasspath.sh
CMD ["tail", "-f", "/dev/null"]
