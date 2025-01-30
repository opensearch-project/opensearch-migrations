# Transformation Playground

This package contains the work-in-progress code for the Transformation Playground, a way for Elasticsearch and OpenSearch users to rapidly visualize the data and metadata within a Source cluster, create and test transformations in a sandbox, perform/test toy-scale migrations in a sandbox, and then kick off an at-scale migration.

### Running the code

#### Backend
To run the backend code locally, use a Python virtual environment.  You'll need AWS Credentials in your AWS Keyring, permissions to invoke Bedrock, and to have onboarded your account to use Claude 3.5 Sonnet (`anthropic.claude-3-5-sonnet-20240620-v1:0`) in `us-west-2`.

```bash
# Start in the repo root

python3 -m venv venv
source venv/bin/activate
pipenv sync --dev

(cd playground && python3 manage.py runserver)
```

This will start a Django REST Framework API running at `http://127.0.0.1:8000`.

You can then hit the API to generate a transform for an Elasticsearch 6.8 Index Settings JSON to OpenSearch 2.17 like so:

```bash
curl -X POST "http://127.0.0.1:8000/transforms/index/" -H "Content-Type: application/json" -d '
{
    "transform_language": "Python",
    "source_version": "Elasticsearch 6.8",
    "target_version": "OpenSearch 2.17",
    "input_shape": {
        "index_name": "test-index",
        "index_json": {
            "settings": {
                "index": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0
                }
            },
            "mappings": {
                "type1": {
                    "properties": {
                        "title": { "type": "text" }
                    }
                },
                "type2": {
                    "properties": {
                        "contents": { "type": "text" }
                    }
                }
            }
        }
    },
    "test_target_url": "http://localhost:29200"
}'
```

The `test_target_url` is optional, but enables the system to test the output Index settings created by running the transform against the starting Index settings against a real OpenSearch domain by creating/deleting them on it.

#### Frontend

If you changed the Backend API specification, you'll first need to generate new client code.  The Backend uses `drf-spectacular` to auto-supply OpenAPI specs to facilitate this process.  You can generate the new client code like so:

```bash
# Start in the repo root

(cd playground && python3 manage.py spectacular --file schema.json)

(cd playground_frontend && npm install && npm run generate-api-client)
```

To run the Frontend, first start the Backend and ensure it's running.  Then, execute the following commands:

```bash
# Start in the repo root

(cd playground_frontend && npm install && npm run dev)
```

You should then be able to hit the Playground website in your web browser at `http://localhost:3000`.  The GUI should be pretty self-explanatory.

Here's an example input JSON for ElasticSearch 6.8:
```json
{
    "index_name": "test-index",
    "index_json": {
        "settings": {
            "index": {
                "number_of_shards": 1,
                "number_of_replicas": 0
            }
        },
        "mappings": {
            "type1": {
                "properties": {
                    "title": {
                        "type": "text"
                    }
                }
            },
            "type2": {
                "properties": {
                    "contents": {
                        "type": "text"
                    }
                }
            }
        }
    }
}
```


Here's an example input JSON for ElasticSearch 7.10:
```json
{
    "index_name": "test-index",
    "index_json": {
        "settings": {
            "index": {
                "soft_deletes": {
                    "enabled": false
                },
                "number_of_shards": 1,
                "number_of_replicas": 1
            }
        },
        "mappings": {
            "properties": {
                "title": {
                    "type": "text"
                },
                "content": {
                    "type": "text"
                }
            }
        }
    }
}
```


### Dependencies
`pipenv` is used to managed dependencies within the project.  The `Pipefile` and `Pipefile.lock` handle the local environment.  You can add dependencies like so:

```
pipenv install boto3
```

This updates the `Pipfile`/`Pipfile.lock` with the new dependency.  To create a local copy of the dependencies, such as for bundling a distribution, you can use pip like so:

```
pipenv requirements > requirements.txt
python3 -m pip install -r requirements.txt -t ./package --upgrade

zip -r9 playground.zip tools/ package/
```