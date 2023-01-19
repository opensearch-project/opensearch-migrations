*** Settings ***
Library    ../robot_lib/OpenSearchRESTActions.py  ${engine_version}  ${host}  ${port}

*** Keywords ***
#Create document
#    [Arguments]    ${index_name}  ${doc}
#    Post doc to index  ${port}  ${index_name}  ${doc}

*** Test Cases ***

# Link to expectation description
Perform pre-upgrade setup of "consistent-document-count" expectation
    [Tags]  expectation::consistent-document-count  stage::pre-upgrade
    Create index  sample-index
    Create document  sample-index  {"color":"blue", "name":"sky"}
    Create document  sample-index  {"color":"green", "name":"grass"}
    Refresh index  sample-index
    ${count} =  Count documents in index  sample-index
    Store data with label  ${count}  consistent-document-count
    Should Be Equal  ${count}  ${2}

Perform post-upgrade assertion of "consistent-document-count" expectation
    [Tags]  expectation::consistent-document-count  stage::post-upgrade
    ${stored_count} =  Retrieve stored data by label  consistent-document-count
    ${count} =  Count documents in index  sample-index
    Should Be Equal  ${stored_count}  ${count}