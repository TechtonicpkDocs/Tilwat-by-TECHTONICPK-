// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import pk.techtonic.tilawa.engine.LITE_MANIFEST
import pk.techtonic.tilawa.engine.ModelManifest
import pk.techtonic.tilawa.engine.OrtRunner
import pk.techtonic.tilawa.engine.OrtStepResult
import pk.techtonic.tilawa.engine.TilawaLoadError
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * [OrtRunner] implemented with ONNX Runtime Android. One instance holds one loaded model; the engine
 * calls [runStep] once per streaming window (every 32 fbank frames, ~320 ms of audio).
 *
 * The model file is passed as a path — the app copies `assets/model.onnx` to its files dir first, because
 * ORT needs a real file. A byte-array constructor is also provided for callers that already hold the
 * model in memory.
 *
 * Input names come from the manifest: `x` plus one cached tensor per state, plus the int64
 * `processed_lens` counter. Outputs are `log_probs` plus `new_<state>` for every state, fed back each
 * step. State tensors are flattened into preallocated arrays sized from the manifest — no boxing on the
 * hot path. `runStep` is not synchronized: the engine calls it from a single thread.
 *
 * Close this when the activity is destroyed; the [OrtEnvironment] itself is a shared singleton and is
 * never closed here.
 */
class OnnxSession private constructor(
    private val session: OrtSession,
    private val manifest: ModelManifest,
) : OrtRunner, Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** Float32 states only; `processed_lens` (int64) travels separately through [runStep]. */
    private val floatStates = manifest.states.filter { it.dtype != "int64" }

    private val inputShape = longArrayOf(1, manifest.window.toLong(), manifest.fbankMels.toLong())

    constructor(modelFilePath: String, manifest: ModelManifest = LITE_MANIFEST) : this(
        createSessionFromFile(modelFilePath),
        manifest,
    )

    constructor(modelBytes: ByteArray, manifest: ModelManifest = LITE_MANIFEST) : this(
        createSessionFromBytes(modelBytes),
        manifest,
    )

    override fun runStep(
        features: FloatArray,
        states: Map<String, FloatArray>,
        processedLens: Long,
    ): OrtStepResult {
        val expectedFeatures = manifest.window * manifest.fbankMels
        require(features.size == expectedFeatures) {
            "Expected $expectedFeatures fbank features (${manifest.window}x${manifest.fbankMels}), got ${features.size}"
        }
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            inputs["x"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), inputShape)
            for (spec in floatStates) {
                val values = states[spec.name]
                    ?: throw IllegalArgumentException("Missing model state ${spec.name}")
                require(values.size == spec.numel()) {
                    "State ${spec.name} has ${values.size} values, expected ${spec.numel()}"
                }
                val shape = LongArray(spec.shape.size) { i -> spec.shape[i].toLong() }
                inputs[spec.name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)
            }
            inputs["processed_lens"] = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(processedLens)),
                longArrayOf(1),
            )
            session.run(inputs).use { result ->
                val logProbsTensor = outputTensor(result, "log_probs")
                val logProbs = flattenFloat(logProbsTensor.value)
                val newStates = LinkedHashMap<String, FloatArray>(floatStates.size)
                for (spec in floatStates) {
                    val tensor = outputTensor(result, "new_${spec.name}")
                    val out = FloatArray(spec.numel())
                    val written = flattenFloatInto(tensor.value, out, 0)
                    if (written != out.size) {
                        throw IllegalStateException(
                            "Model output new_${spec.name} has $written values, expected ${out.size}"
                        )
                    }
                    newStates[spec.name] = out
                }
                val lensTensor = outputTensor(result, "new_processed_lens")
                val newProcessedLens = extractLong(lensTensor.value)
                return OrtStepResult(logProbs, newStates, newProcessedLens)
            }
        } finally {
            for (tensor in inputs.values) tensor.close()
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** `Result.get(name)` returns an Optional in ORT 1.30: unwrap it, then cast, then require it. */
        private fun outputTensor(result: OrtSession.Result, name: String): OnnxTensor =
            result.get(name).orElse(null) as? OnnxTensor
                ?: throw IllegalStateException("Model output $name is missing")

        private fun sessionOptions(): OrtSession.SessionOptions {
            val options = OrtSession.SessionOptions()
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            return options
        }

        private fun createSessionFromFile(path: String): OrtSession {
            return try {
                OrtEnvironment.getEnvironment().createSession(path, sessionOptions())
            } catch (e: Exception) {
                throw TilawaLoadError("model", e)
            }
        }

        private fun createSessionFromBytes(bytes: ByteArray): OrtSession {
            return try {
                OrtEnvironment.getEnvironment().createSession(bytes, sessionOptions())
            } catch (e: Exception) {
                throw TilawaLoadError("model", e)
            }
        }

        /** Flattens a nested float tensor value (innermost dimension a FloatArray) into one array. */
        private fun flattenFloat(value: Any): FloatArray {
            val out = ArrayList<Float>()
            flattenFloatIntoList(value, out)
            return out.toFloatArray()
        }

        private fun flattenFloatIntoList(value: Any, out: MutableList<Float>) {
            when (value) {
                is FloatArray -> value.forEach { out.add(it) }
                is Array<*> -> value.forEach { it?.let { flattenFloatIntoList(it, out) } }
                else -> throw IllegalStateException("Unexpected float tensor element ${value.javaClass}")
            }
        }

        /** Flattens a nested float tensor value into `out` starting at `offset`; returns the end offset. */
        private fun flattenFloatInto(value: Any, out: FloatArray, offset: Int): Int {
            return when (value) {
                is FloatArray -> {
                    value.copyInto(out, offset)
                    offset + value.size
                }
                is Array<*> -> {
                    var o = offset
                    for (v in value) {
                        if (v != null) o = flattenFloatInto(v, out, o)
                    }
                    o
                }
                else -> throw IllegalStateException("Unexpected float tensor element ${value.javaClass}")
            }
        }

        /** Extracts the single long from a scalar or 1-element int64 tensor value, any nesting depth. */
        private fun extractLong(value: Any): Long {
            return when (value) {
                is LongArray -> {
                    if (value.isEmpty()) throw IllegalStateException("Empty processed_lens output")
                    value[0]
                }
                is Array<*> -> {
                    val first = value.firstOrNull()
                        ?: throw IllegalStateException("Empty processed_lens output")
                    extractLong(first)
                }
                is Long -> value
                else -> throw IllegalStateException("Unexpected processed_lens value ${value.javaClass}")
            }
        }
    }
}
