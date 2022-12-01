from typing import List
"""
Exceptions are a useful mechanism for communicating between abstraction layers in an application.  As such, they
should always be named and thrown for the benefit of the layer that receives them.  That means their names and messages
should be understandable from the context of the layer that receives them, whether that's code or a human.

One key abstraction boundary in the Upgrade Testing Framework is the one between the individual FrameworkSteps being
run and the FrameworkRunner invoking them.  To help protect and formalize this boundary, we will have the FrameworkSteps
capture all exceptions that bubble up to the top of their stack and re-thrown them as a FrameworkException or one of its
descendants.  This provides a couple of benefits:
    * It makes it more obvious when things went VERY wrong in our application (e.g. the top-level loop,
        FrameworkRunner, should only see FrameworkExceptions)
    * It helps with messaging control.  A typical user will probably care about why a Step failed at a higher level than
        provided by the actual exception(s) that were thrown by the application code.
    * It enables us to make extensive use of custom exceptions to communicate between lower levels of our application
        while making a distinction between those exceptions we expect to only be communications between lower layers
        and exceptions that will kill the overall application
    * It enables us to do things later like categorize our exceptions into low/medium/high priority buckets for support
        purposes (e.g. a HighPriorityException was something we really didn't expect to see and should investigate while
        a LowPriorityException is something we can deprioritize because we had evidence it was a user-fault)

When should you instantiate a FrameworkException or create a new descendent of it?  Rarely.  By throwing a
FrameworkException yourself, you are effectively asserting that you know the exception in question will bubble up to
the top level of the call stack and kill the application.  Since you probably don't know that, you probably shouldn't
throw one and let the overall Framework handle that detail.
"""

# Ultimate base class of all exceptions we expect to kill the app
class FrameworkException(Exception):
    def __init__(self, message: str, original_exception: BaseException = None, *args, **kwargs):
        super().__init__(message, *args, **kwargs)
        self.original_exception = original_exception

# Exceptions that we encounter but aren't specifically looking for and handling in other ways
class RuntimeFrameworkException(FrameworkException):
    def __init__(self, message: str, original_exception: BaseException, *args, **kwargs):
        super().__init__(message, original_exception=original_exception, *args, **kwargs)

# Exception thrown when we have reached an unrecoverable place in a FrameworkStep and need to exit it, and therefore
# kill the app
class StepFailedException(FrameworkException):
    pass

# Used to communicate that the user requested the application exit, mostly like due to a SIGINT
class UserAbortException(FrameworkException):
    pass

def is_exception_in_type_list(exception: BaseException, type_list: list):
    for exc_type in type_list:
        if isinstance(exception, exc_type):
            return True
    return False