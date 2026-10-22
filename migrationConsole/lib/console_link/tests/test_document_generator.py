import importlib.util
import pathlib


GENERATOR_PATH = pathlib.Path(__file__).parents[4].joinpath(
    "TrafficCapture",
    "dockerSolution",
    "src",
    "main",
    "docker",
    "elasticsearchTestConsole",
    "testDocumentGenerator.py",
)


def load_generator_module():
    spec = importlib.util.spec_from_file_location("testDocumentGenerator", GENERATOR_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_generate_tenant_ids_uses_excel_style_labels():
    generator = load_generator_module()

    assert generator.generate_tenant_ids(2) == ["tenant-a", "tenant-b"]
    assert generator.generate_tenant_ids(50)[-3:] == ["tenant-av", "tenant-aw", "tenant-ax"]


def test_generate_small_doc_includes_tenant_id_when_provided():
    generator = load_generator_module()

    doc = generator.generate_small_doc(doc_size_bytes=150, tenant_id="tenant-a")

    assert doc["tenant_id"] == "tenant-a"
