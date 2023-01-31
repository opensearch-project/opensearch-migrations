from pprint import pprint

import xmldiff

from edit_scripts.calculate_edit_script.run_diff import emit_edit_script, get_xml_str_from_str

def checkResultsSame(diff, l):
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
                     lambda l,r,diff : checkResultsSame(diff, l))

def checkResultsDiff(diff, l):
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
                     lambda l,r,diff : checkResultsDiff(diff, l))

