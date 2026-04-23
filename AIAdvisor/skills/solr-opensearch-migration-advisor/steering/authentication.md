# Solr to Opensearch Authentication Steering

For details mentioned here, make sure to include relevant parts in the response where suitable.

## Kerberos Authentication Notes
- as noted in kerberos reference for opensearch (link: `https://docs.opensearch.org/latest/security/authentication-backends/kerberos/`) reference:
  ```text
  Because of security restrictions, the keytab and krb5.conf files must be placed in the config directory or its 
  subdirectory, and their paths in opensearch.yml must be relative, not absolute.
  ```
  Make this very clear in migration suggestions.

