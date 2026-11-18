// Ported from Tilawa (@tilawa/core, MIT, https://github.com/yazinsai/tilawa) — engine logic by yazinsai, Kotlin port by TECHTONICPK.
package pk.techtonic.tilawa.engine

data class StateSpec(val name: String, val shape: List<Int>, val dtype: String) {
    fun numel(): Int = shape.fold(1) { acc, d -> acc * d }
}

/** `manifest.json` next to each exported model (written by `modal/student_export.py`). */
data class ModelManifest(
    val schema: Int,
    val id: String,
    val format: String,
    val arch: String,
    /** Must equal the reference index inventory id. */
    val inventory: String,
    /** Token `i + 1` is `tokens[i]`; 0 is the CTC blank. */
    val tokens: String,
    val blank: Int,
    val sampleRate: Int,
    val frameMs: Int,
    val fbankMels: Int,
    val fbankPadValue: Double,
    /** Fbank frames per call and frames to advance by. */
    val window: Int,
    val shift: Int,
    val leftContext: Int,
    val states: List<StateSpec>,
    val file: String,
    val bytes: Long,
    val sha256: String,
    /** Error classes this model's evidence supports; the rest are reported `unsupported`. */
    val supported: List<String>,
)

fun parseModelManifest(json: String): ModelManifest {
    val o = Json.parse(json).asObject()
    val fb = o.obj("fbank")
    return ModelManifest(
        schema = o.int("schema"),
        id = o.str("id"),
        format = o.str("format"),
        arch = o.str("arch"),
        inventory = o.str("inventory"),
        tokens = o.str("tokens"),
        blank = o.int("blank"),
        sampleRate = o.int("sampleRate"),
        frameMs = o.int("frameMs"),
        fbankMels = fb.int("mels"),
        fbankPadValue = fb.double("padValue"),
        window = o.int("window"),
        shift = o.int("shift"),
        leftContext = if (o.has("leftContext")) o.int("leftContext") else 0,
        states = o.arr("states").objects().map { s ->
            StateSpec(s.str("name"), s.arr("shape").ints(), s.str("dtype"))
        },
        file = o.str("file"),
        bytes = o.long("bytes"),
        sha256 = o.str("sha256"),
        supported = if (o.has("supported")) o.arr("supported").strings() else MEMORIZATION_CLASSES,
    )
}

/**
 * The shipped model's manifest (spliced-clean-lite, the lite tier = Android default), embedded verbatim from
 * models-v1.json so the app needs no manifest file at runtime. The golden harness parses the tiny fixture manifest
 * from disk instead (same parser).
 */
private const val LITE_MANIFEST_JSON = """{"schema":1,"id":"spliced-clean-lite","format":"onnx-int8","arch":"zipformer2-streaming-ctc","inventory":"muaalem-v3_2-phonemes","tokens":"\u0621\u0628\u062a\u062b\u062c\u062d\u062e\u062f\u0630\u0631\u0632\u0633\u0634\u0635\u0636\u0637\u0638\u0639\u063a\u0641\u0642\u0643\u0644\u0645\u0646\u0647\u0648\u064a\u0627\u06e6\u06e5\u064e\u064f\u0650\u06ea\u0640\u0672\u0687\u06ba\u06fe\u06dc\u0619","blank":0,"sampleRate":16000,"frameMs":40,"fbank":{"mels":80,"frameShiftMs":10,"frameLengthMs":25,"highFreq":-400,"snipEdges":true,"padValue":-23.0259},"window":45,"shift":32,"chunk":16,"leftContext":128,"states":[{"name":"cached_key_0","shape":[128,1,128],"dtype":"float32"},{"name":"cached_nonlin_attn_0","shape":[1,1,128,144],"dtype":"float32"},{"name":"cached_val1_0","shape":[128,1,48],"dtype":"float32"},{"name":"cached_val2_0","shape":[128,1,48],"dtype":"float32"},{"name":"cached_conv1_0","shape":[1,192,15],"dtype":"float32"},{"name":"cached_conv2_0","shape":[1,192,15],"dtype":"float32"},{"name":"cached_key_1","shape":[128,1,128],"dtype":"float32"},{"name":"cached_nonlin_attn_1","shape":[1,1,128,144],"dtype":"float32"},{"name":"cached_val1_1","shape":[128,1,48],"dtype":"float32"},{"name":"cached_val2_1","shape":[128,1,48],"dtype":"float32"},{"name":"cached_conv1_1","shape":[1,192,15],"dtype":"float32"},{"name":"cached_conv2_1","shape":[1,192,15],"dtype":"float32"},{"name":"cached_key_2","shape":[64,1,128],"dtype":"float32"},{"name":"cached_nonlin_attn_2","shape":[1,1,64,192],"dtype":"float32"},{"name":"cached_val1_2","shape":[64,1,48],"dtype":"float32"},{"name":"cached_val2_2","shape":[64,1,48],"dtype":"float32"},{"name":"cached_conv1_2","shape":[1,256,15],"dtype":"float32"},{"name":"cached_conv2_2","shape":[1,256,15],"dtype":"float32"},{"name":"cached_key_3","shape":[64,1,128],"dtype":"float32"},{"name":"cached_nonlin_attn_3","shape":[1,1,64,192],"dtype":"float32"},{"name":"cached_val1_3","shape":[64,1,48],"dtype":"float32"},{"name":"cached_val2_3","shape":[64,1,48],"dtype":"float32"},{"name":"cached_conv1_3","shape":[1,256,15],"dtype":"float32"},{"name":"cached_conv2_3","shape":[1,256,15],"dtype":"float32"},{"name":"cached_key_4","shape":[32,1,128],"dtype":"float32"},{"name":"cached_nonlin_attn_4","shape":[1,1,32,192],"dtype":"float32"},{"name":"cached_val1_4","shape":[32,1,48],"dtype":"float32"},{"name":"cached_val2_4","shape":[32,1,48],"dtype":"float32"},{"name":"cached_conv1_4","shape":[1,256,7],"dtype":"float32"},{"name":"cached_conv2_4","shape":[1,256,7],"dtype":"float32"},{"name":"cached_key_5","shape":[32,1,128],"dtype":"float32"},{"name":"cached_nonlin_attn_5","shape":[1,1,32,192],"dtype":"float32"},{"name":"cached_val1_5","shape":[32,1,48],"dtype":"float32"},{"name":"cached_val2_5","shape":[32,1,48],"dtype":"float32"},{"name":"cached_conv1_5","shape":[1,256,7],"dtype":"float32"},{"name":"cached_conv2_5","shape":[1,256,7],"dtype":"float32"},{"name":"cached_key_6","shape":[16,1,256],"dtype":"float32"},{"name":"cached_nonlin_attn_6","shape":[1,1,16,192],"dtype":"float32"},{"name":"cached_val1_6","shape":[16,1,96],"dtype":"float32"},{"name":"cached_val2_6","shape":[16,1,96],"dtype":"float32"},{"name":"cached_conv1_6","shape":[1,256,7],"dtype":"float32"},{"name":"cached_conv2_6","shape":[1,256,7],"dtype":"float32"},{"name":"cached_key_7","shape":[16,1,256],"dtype":"float32"},{"name":"cached_nonlin_attn_7","shape":[1,1,16,192],"dtype":"float32"},{"name":"cached_val1_7","shape":[16,1,96],"dtype":"float32"},{"name":"cached_val2_7","shape":[16,1,96],"dtype":"float32"},{"name":"cached_conv1_7","shape":[1,256,7],"dtype":"float32"},{"name":"cached_conv2_7","shape":[1,256,7],"dtype":"float32"},{"name":"cached_key_8","shape":[32,1,128],"dtype":"float32"},{"name":"cached_nonlin_attn_8","shape":[1,1,32,192],"dtype":"float32"},{"name":"cached_val1_8","shape":[32,1,48],"dtype":"float32"},{"name":"cached_val2_8","shape":[32,1,48],"dtype":"float32"},{"name":"cached_conv1_8","shape":[1,256,7],"dtype":"float32"},{"name":"cached_conv2_8","shape":[1,256,7],"dtype":"float32"},{"name":"cached_key_9","shape":[32,1,128],"dtype":"float32"},{"name":"cached_nonlin_attn_9","shape":[1,1,32,192],"dtype":"float32"},{"name":"cached_val1_9","shape":[32,1,48],"dtype":"float32"},{"name":"cached_val2_9","shape":[32,1,48],"dtype":"float32"},{"name":"cached_conv1_9","shape":[1,256,7],"dtype":"float32"},{"name":"cached_conv2_9","shape":[1,256,7],"dtype":"float32"},{"name":"cached_key_10","shape":[64,1,128],"dtype":"float32"},{"name":"cached_nonlin_attn_10","shape":[1,1,64,192],"dtype":"float32"},{"name":"cached_val1_10","shape":[64,1,48],"dtype":"float32"},{"name":"cached_val2_10","shape":[64,1,48],"dtype":"float32"},{"name":"cached_conv1_10","shape":[1,256,15],"dtype":"float32"},{"name":"cached_conv2_10","shape":[1,256,15],"dtype":"float32"},{"name":"cached_key_11","shape":[64,1,128],"dtype":"float32"},{"name":"cached_nonlin_attn_11","shape":[1,1,64,192],"dtype":"float32"},{"name":"cached_val1_11","shape":[64,1,48],"dtype":"float32"},{"name":"cached_val2_11","shape":[64,1,48],"dtype":"float32"},{"name":"cached_conv1_11","shape":[1,256,15],"dtype":"float32"},{"name":"cached_conv2_11","shape":[1,256,15],"dtype":"float32"},{"name":"embed_states","shape":[1,128,3,19],"dtype":"float32"},{"name":"processed_lens","shape":[1],"dtype":"int64"}],"file":"model.onnx","bytes":24650054,"sha256":"4974de78d3c35d2542e7a24d99b747e1160d6e31e366fb68492b74a789890f1b","trainStep":30287,"supported":["wrong_word","omitted_word","added_word","skipped_passage"],"quantizedFrom":"3af366b9ae5c2587293748b2a09ea664c531206112829bfcd88ca84edcb544ab"}"""

val LITE_MANIFEST: ModelManifest by lazy { parseModelManifest(LITE_MANIFEST_JSON) }
