This part of the code is being removed because the dependencies that were in the elasticsearch logging plugin gradle
file were causing a lot of CVE issues to appear in the mend security check. specifically, due to it using the netty
client for elasticsearch 7.10.2 , which has alot of dependencies that have/cause security issues.

This is the SHA of the commit that originally added these files:
1107701f9940358afb9bd1c61384aaa8c2093cde