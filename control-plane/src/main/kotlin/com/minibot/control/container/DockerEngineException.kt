package com.minibot.control.container

class DockerEngineException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
