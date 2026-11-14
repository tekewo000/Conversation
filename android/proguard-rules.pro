# JNI and separately signed diagnostics resolve app types and members by their original names.
-keep,allowoptimization class jp.tekewo.conversation.** { *; }
# ONNX JNI resolves Java types and constructors by name when returning model output.
-keep class ai.onnxruntime.** { *; }
