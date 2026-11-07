# 本地启动脚本（已实测可用）
#
# 为什么用显式 --codecanvas.* 参数而不是只靠环境变量：
#   实测发现：仅设 CODECANVAS_OPENAI_KEY / CODECANVAS_MODEL_* 时，
#   /api/config 仍报 llmMode=MOCK 且模型名回落成类里的默认值（deepseek-*）；
#   而用命令行属性显式传入后立刻变成 llmMode=REAL。
#   所以这里把关键配置显式写进启动参数，绕开占位符解析的不确定性。
#
# 用法：
#   $env:CODECANVAS_OPENAI_KEY = "sk-你的百炼key"      # 或先 setx 一次，然后重开终端
#   .\start-local.ps1

param(
    [string]$ApiKeyGate = "dev-local-key",   # 前端 VITE_API_KEY 必须与之一致
    [string]$Model      = "qwen3-coder-flash",
    [string]$BaseUrl    = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    [string]$Jar        = "target\codecanvas-backend.jar"
)

$llmKey = $env:CODECANVAS_OPENAI_KEY
if (-not $llmKey) { $llmKey = [Environment]::GetEnvironmentVariable('CODECANVAS_OPENAI_KEY', 'User') }
if (-not $llmKey) {
    Write-Host "缺少模型密钥：请先设置 CODECANVAS_OPENAI_KEY" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $Jar)) {
    Write-Host "找不到 $Jar，请先执行： mvn -B -DskipTests package" -ForegroundColor Red
    exit 1
}

Write-Host "启动 CodeCanvas：model=$Model  api-key=***$($llmKey.Substring([Math]::Max(0,$llmKey.Length-4)))" -ForegroundColor Cyan

$arguments = @(
    '-jar', $Jar,
    '--codecanvas.llm.mode=real',
    "--codecanvas.llm.openai.api-key=$llmKey",
    "--codecanvas.llm.openai.base-url=$BaseUrl",
    "--codecanvas.llm.models.flagship=$Model",
    "--codecanvas.llm.models.standard=$Model",
    "--codecanvas.llm.models.light=$Model",
    "--codecanvas.api.key=$ApiKeyGate"
)
& java @arguments