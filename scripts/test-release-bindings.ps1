param([Parameter(Mandatory)][string]$Mapping)
$ErrorActionPreference = 'Stop'
$lines = Get-Content -LiteralPath $Mapping
# ONNX Runtime's JNI looks up these types by their original Java names.
foreach ($type in @('OnnxJavaType', 'OrtEnvironment', 'OrtException', 'OrtException$OrtErrorCode', 'OrtLoggingLevel', 'TensorInfo', 'TensorInfo$OnnxTensorType')) {
    $name = "ai.onnxruntime.$type"
    if ($lines -notcontains "$name -> ${name}:") {
        throw "Release changed or removed ONNX JNI type: $name. Check android/proguard-rules.pro."
    }
}
Write-Host 'PASS: Release preserves the ONNX Runtime JNI type names.'
