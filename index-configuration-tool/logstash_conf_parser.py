import sys
from lark import Lark
from lark import Transformer


# The names of each function in the Transformer corresponds
# to
class LogstashTransformer(Transformer):
    def var_name(self, v: list):
        (v,) = v
        return v.value

    def string_literal(self, s: list):
        s = self.var_name(s)
        # Remove surrounding quotes
        return s[1:-1]

    def number(self, n: list):
        (n,) = n
        return float(n)

    def true(self, b):
        return True

    def false(self, b):
        return False

    # The same logic is applied for both rules
    key = var_name
    type = var_name
    # These rules can be transformed directly to a corresponding Python type
    start = dict
    config_section = tuple
    plugin_params = dict
    list = list
    param = tuple
    plugin = tuple


logstash_parser = Lark.open("logstash.lark", rel_to=__file__, parser="lalr", transformer=LogstashTransformer())


def parse(logstash_file: str) -> dict:
    with open(logstash_file, "r") as conf_file:
        return logstash_parser.parse(conf_file.read())


if __name__ == '__main__':
    val = parse(sys.argv[1])
    print(val)
