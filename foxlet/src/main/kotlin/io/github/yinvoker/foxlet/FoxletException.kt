package io.github.yinvoker.foxlet

import java.io.File

/** Recoverable SDK failure. Coroutine cancellation is never wrapped in this hierarchy. */
sealed class FoxletException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ClientClosedException : IllegalStateException("Foxlet has been shut down")
class ModelNotInstalledException(val pair: LanguagePair) : FoxletException("No usable local model for " + pair)
class UnsupportedLanguagePairException(val pair: LanguagePair) : FoxletException("No bundled direct model for " + pair)
class ModelInUseException(val installationId: InstallationId? = null, val pair: LanguagePair? = null) :
    FoxletException("Model files are in use; stop submitting translations and unload models before deleting")
class ModelIntegrityException(
    message: String,
    val installationId: InstallationId? = null,
    val assetName: String? = null,
    cause: Throwable? = null,
) : FoxletException(message, cause)
class NetworkException(
    message: String,
    val url: String,
    val httpStatus: Int? = null,
    val attempt: Int = 1,
    val assetName: String? = null,
    cause: Throwable? = null,
) : FoxletException(message, cause)
class ModelStorageException(message: String, val directory: File, cause: Throwable? = null) : FoxletException(message, cause)
class ModelIndexException(message: String, cause: Throwable? = null) : FoxletException(message, cause)
class TranslationException(message: String, cause: Throwable? = null) : FoxletException(message, cause)
