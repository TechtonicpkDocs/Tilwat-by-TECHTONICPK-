// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

/** `pushAudio`, `pushEvidence` or `end` on a session that has ended or been disposed. */
class SessionEndedError(message: String = "The session has ended") : Exception(message)

typealias LoadStage = String

/** `createTilawa` could not load its model or reference. `cause` is the underlying error. */
class TilawaLoadError(val stage: LoadStage, cause: Throwable) :
    Exception("Could not load the $stage: ${cause.message}", cause)

typealias MicrophoneErrorReason = String

/** `listen()` could not open the microphone. */
class MicrophoneError(val reason: MicrophoneErrorReason, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Model file failed its integrity check (size or sha256). */
class ModelIntegrityError(message: String) : Exception(message)

/** Model exceeds the configured budget (bytes). */
class ModelBudgetError(message: String) : Exception(message)
