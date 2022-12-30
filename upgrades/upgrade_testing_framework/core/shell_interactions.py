import logging
import os
import re
from typing import List, Tuple

import pexpect

logger = logging.getLogger(__name__)


def call_shell_command(command: str, cwd: str = None, env: str = None,
                       request_response_pairs: List[Tuple[str, str]] = [], suppress_stdout: bool = False):
    """
    Execute a command in a child shell process.  By default, stdout from the child process is logged at the DEBUG
    level.

    The user can optionally supply a list of request/response pairs in order to handle command invocations that expect
    a user response.  For example, if the shell command invocation requires the user to confirm an action with a "yes"
    response, the user might specify an argument value like:

        call_shell_command('my_shell_command', request_response_pairs=[('Do you really want to?', 'yes')])

    The method will search for the phrase "Do you really want to?" in the output of the command my_shell_command and
    pipe through the response "yes" if it finds it.  Any number of pairs can be supplied, and the method will continue
    searching the output and piping through responses until none of the pair match.
    """
    logger.debug("Running command: {}".format(command))
    if cwd:
        logger.debug("Running command in directory: {}".format(cwd))

    std_out = []
    process_handle = pexpect.spawn(command, cwd=cwd, env=env, timeout=None)

    while True:
        # Get the list of things we're looking for to delineate the end of the next chunk of output
        default_expectation = [os.linesep, pexpect.EOF]  # by default, we just look for line seps and then end of output
        expectations = [pair[0] for pair in request_response_pairs]  # look for any user-provided values first
        expectations.extend(default_expectation)  # then our default stuff

        responses = [pair[1] for pair in request_response_pairs]
        match_number = process_handle.expect(expectations)

        if match_number < len(responses):  # if we found a user-supplied value, then supply the response
            logger.debug("Found Match: {}".format(match_number))
            process_handle.sendline(responses[match_number])

        _store_and_print_output(process_handle.before, std_out, suppress_stdout)

        if match_number == len(expectations) - 1:  # EOF, i.e. the end of the output
            break

    process_handle.close()
    return (process_handle.exitstatus, std_out)


def _store_and_print_output(output, container, suppress):
    if output:
        unicode_output = output.decode().strip()
        container.append(unicode_output)
        if not suppress:
            no_ansi_codes = _remove_ansi_codes(unicode_output)
            logger.debug(no_ansi_codes)


def _remove_ansi_codes(text: str):
    # See: https://en.wikipedia.org/wiki/ANSI_escape_code
    return re.sub('(\\[[0-9]+m|\x1b)', '', text)


def remove_ansi_escape_sequences(string_to_clean: str):
    """
    Method to remove ANSI escaped sequences from an input string.  Useful for removing wacky instructions that may
    impact output to terminal/logs, or screw up regexes looking for specific tex.
    """
    # See: https://en.wikipedia.org/wiki/ANSI_escape_code
    ansi_escape = re.compile(r'\x1B\[[0-?]*[ -/]*[@-~]')
    return ansi_escape.sub('', string_to_clean)
