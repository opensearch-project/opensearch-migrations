import json

js_file = "MetadataMigration/src/test/resources/es2-transforms.js"
json_file = "MetadataMigration/src/test/resources/es2-transforms.json"

with open(js_file, "r", encoding="utf-8") as f:
    script_content = " ".join(line.strip() for line in f.readlines())
with open(json_file, "r", encoding="utf-8") as f:
    json_data = json.load(f)
json_data[0]["JsonJSTransformerProvider"]["initializationScript"] = script_content
with open(json_file, "w", encoding="utf-8") as f:
    json.dump(json_data, f, indent=4)

print("Updated!")
