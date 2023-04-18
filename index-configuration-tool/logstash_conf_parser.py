import sys
from lark import Lark
from lark import Transformer

class LogstashTransformer(Transformer):
    def stringValue(self, s):
        return s.value
    def string_literal(self, s):
        (s,) = s
        return s[1:-1]
    def number(self, n):
        (n,) = n
        return float(n)

    start = dict
    config_section = tuple
    plugin_params = dict
    list = list
    param = tuple
    plugin = tuple
    STRING = stringValue
    PLUGIN_TYPE = stringValue

    true = lambda self, _: True
    false = lambda self, _: False

logstash_parser = Lark.open("logstash.lark", rel_to=__file__, parser="lalr", transformer=LogstashTransformer())

def parse(logstash_file):
    conf_file = open(logstash_file, "r")
    res = logstash_parser.parse(conf_file.read())
    conf_file.close()
    return res

if __name__ == '__main__':
    val = parse(sys.argv[1])
    print(val)