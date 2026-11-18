# Keep ONNX Runtime classes (used via reflection-free API but the native
# bridge must not be renamed).
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
