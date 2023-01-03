*** Settings ***
Library    ../lib/OpenSearchClientLibrary.py  ${host}  ${port}

*** Keywords ***
#Create an index
#    [Arguments]    ${index_name}
#    Create index   ${index_name}

*** Test Cases ***

# Link to expectation description
Perform pre-upgrade setup of "consistent-document-count" expectation
    [Tags]  consistent-document-count  pre-upgrade
    Create index  sample-index
    Create document in index  sample-index  {"color":"blue", "name":"sky"}
    Create document in index  sample-index  {"color":"green", "name":"grass"}
    Refresh index  sample-index
    ${count} =  Count documents in index  sample-index
    Store data with label  ${count}  consistent-document-count
    Should Be Equal  ${count}  ${2}

Perform post-upgrade assertion of "consistent-document-count" expectation
    [Tags]  consistent-document-count  post-upgrade
    ${stored_count} =  Retrieve stored data by label  consistent-document-count
    ${count} =  Count documents in index  sample-index
    Should Be Equal  ${stored_count}  ${count}