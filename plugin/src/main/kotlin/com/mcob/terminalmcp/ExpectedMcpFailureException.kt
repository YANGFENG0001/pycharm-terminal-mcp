package com.mcob.terminalmcp

open class ExpectedMcpFailureException(message: String) : RuntimeException(message)

class TerminalAccessDeniedException(message: String) : ExpectedMcpFailureException(message)

class TerminalNotFoundException(message: String) : ExpectedMcpFailureException(message)

class TerminalWidgetUnavailableException(message: String) : ExpectedMcpFailureException(message)

class RemoteTransferExpectedFailureException(message: String) : ExpectedMcpFailureException(message)

fun expectedMcpFailure(message: String): Nothing = throw ExpectedMcpFailureException(message)
