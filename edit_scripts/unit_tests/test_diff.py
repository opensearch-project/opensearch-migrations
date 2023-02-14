from pprint import pprint

import xmldiff

from calculate_edit_script.run_diff import emit_edit_script, get_xml_str_from_str


def checkResultsSame(diff, leftTree):
    ldiff = list(diff)
    assert len(ldiff) == 0


def test_diff_on_same_is_empty():
    json1 = """
    {
      "buckets" : [  {
        "key" : "AL",
        "doc_count" : 25
      }, {
        "key" : "MD",
        "doc_count" : 25
      }, {
        "key" : "TN",
        "doc_count" : 23
      } ]
    }"""

    emit_edit_script(get_xml_str_from_str(json1), get_xml_str_from_str(json1),
                     lambda leftTree, rightTree, diff: checkResultsSame(diff, leftTree))


def checkResultsDiff(diff, leftTree):
    ldiff = list(diff)
    assert len(ldiff) == 1
    assert ldiff[0].__class__ == xmldiff.actions.MoveNode


def test_easy_rotate_works():
    json1 = """
    {
      "buckets" : [  {
        "key" : "A",
        "doc_count" : 1
      }, {
        "key" : "B",
        "doc_count" : 2
      }, {
        "key" : "C",
        "doc_count" : 3
      } ]
    }"""
    json2 = """
    {
      "buckets" : [  {
        "key" : "B",
        "doc_count" : 2
      }, {
        "key" : "C",
        "doc_count" : 3
      }, {
        "key" : "A",
        "doc_count" : 1
      } ]
    }"""

    emit_edit_script(get_xml_str_from_str(json1), get_xml_str_from_str(json2),
                     lambda leftTree, rightTree, diff: checkResultsDiff(diff, leftTree))


def checkResultsRotateUpdate(diff, leftTree):
    ldiff = list(diff)
    pprint(ldiff)
    assert len(ldiff) == 2
    assert ldiff[0].__class__ == xmldiff.actions.MoveNode


def test_easy_rotate_with_update_works():
    json1 = """
    {
      "buckets" : [  {
        "key" : "A",
        "doc_count" : 1000
      }, {
        "key" : "B",
        "doc_count" : 2
      }, {
        "key" : "C",
        "doc_count" : 3
      } ]
    }"""
    json2 = """
    {
      "buckets" : [  {
        "key" : "B",
        "doc_count" : 2
      }, {
        "key" : "C",
        "doc_count" : 3
      }, {
        "key" : "A",
        "doc_count" : 1001
      } ]
    }"""

    xml1 = get_xml_str_from_str(json1)
    xml2 = get_xml_str_from_str(json2)
    pprint(xml1)
    pprint(xml2)
    emit_edit_script(xml1, xml2,
                     lambda leftTree, rightTree, diff: checkResultsRotateUpdate(diff, leftTree))


def main():
    test_easy_rotate_with_update_works()


if __name__ == "__main__":
    main()