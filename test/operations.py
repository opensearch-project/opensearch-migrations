import requests, json

class Operations:
    @staticmethod
    def create_index(endpoint, index_name, auth=None, data=None):
        response = requests.put(f'{endpoint}/{index_name}', auth=auth)
        if response.status_code != 200:
            print('Failed to create index')
            print(response.text)
        else:
            print('Created index successfully')
            print(response.text)

        pass

    @staticmethod
    def delete_index(endpoint, index_name, auth=None, data=None):
        response = requests.delete(f'{endpoint}/{index_name}', auth=auth)
        if response.status_code != 200:
            print('Failed to delete index')
            print(response.text)
        else:
            print('Deleted index successfully')
            print(response.text)

    pass


    @staticmethod
    def create_document(endpoint, index_name, doc_id, auth=None):
        document = {
            'title': 'Test Document',
            'content': 'This is a sample document for testing OpenSearch.'
        }
        url = f'{endpoint}/{index_name}/_doc/{doc_id}'
        headers = {'Content-Type': 'application/json'}

        response = requests.put(url, headers=headers, data=json.dumps(document), auth=auth)

        if response.status_code != 201:
            print('Failed to create document')
            print(response.text)
        else:
            print('Created document successfully')
            print(response.text)
        pass



def main():

    username = '*'  # Enter master username and password
    password = '*'
    auth = (username, password)
    endpoint = '*'   # Replace * with domain endpoint
    index = 'my_index'
    doc_id = '7'

    Operations.create_index(endpoint, index, auth)
    Operations.create_document(endpoint, index, doc_id, auth)
    Operations.delete_index(endpoint, index, auth)


if __name__ == "__main__":
    main()