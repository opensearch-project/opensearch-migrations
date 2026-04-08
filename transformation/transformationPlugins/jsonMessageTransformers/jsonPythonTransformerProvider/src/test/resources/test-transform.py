def main(context):
    def transform(document):
        document['transformed_by'] = 'resource_script'
        return document
    return transform


main
