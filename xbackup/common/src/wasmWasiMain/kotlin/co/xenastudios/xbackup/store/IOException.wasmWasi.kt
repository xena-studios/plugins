package co.xenastudios.xbackup.store

public actual open class IOException actual constructor(message: String?, cause: Throwable?) : Exception(message, cause)
