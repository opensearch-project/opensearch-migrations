from json2xml import json2xml
from json2xml.utils import readfromjson, readfromstring
from xmldiff import formatting as xmlformatting

import argparse


def get_command_line_args():
    parser = argparse.ArgumentParser(description="Script to compare json files.")
    parser.add_argument('filenames', metavar='filename', type=str, nargs=2,
                        help='filenames to evaluate for differences')
    return parser.parse_args()


def get_xml_str_from_file(filename: str):
    # get the xml from a json string
    data = readfromjson(filename)
    return get_xml_str(data)


def get_xml_str_from_str(text: str):
    data = readfromstring(text)
    return get_xml_str(data)


def get_xml_str(jsonStr: str):
    # get the xml from a json string
    return json2xml.Json2xml(jsonStr).to_xml()


def emit_edit_script(jsonStr1: str, jsonStr2: str, callback):
    from lxml import etree
    parser = etree.XMLParser(remove_blank_text=True)
    left_tree = etree.fromstring(jsonStr1, parser)
    right_tree = etree.fromstring(jsonStr2, parser)

    from xmldiff import diff
    differ = diff.Differ(ratio_mode='accurate', F=0.01)
    diffResult = differ.diff(left_tree, right_tree)
    if (callback is not None):
        callback(left_tree, right_tree, diffResult)
    return diffResult


def main():
    args = get_command_line_args()
    leftXmlStr = get_xml_str(args.filenames[0])
    rightXmlStr = get_xml_str(args.filenames[1])

    formatter = xmlformatting.DiffFormatter()
    emit_edit_script(leftXmlStr, rightXmlStr, lambda l, r, diff: print(formatter.format(diff, l)))


if __name__ == "__main__":
    main()
