import logging
import typing

from console_link.models.tuple_reader import TupleReader
logger = logging.getLogger(__name__)


def convert(inputfile: typing.TextIO, ouptutfile: typing.TextIO):
    tuple_reader = TupleReader()
    tuple_reader.transform_stream(inputfile, ouptutfile)
