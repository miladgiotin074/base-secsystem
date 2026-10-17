# Run after installing Ollama and pulling qwen2.5:3b
$base = if ($env:REALTIME_AI_URL) { $env:REALTIME_AI_URL.TrimEnd('/') } else { "http://127.0.0.1:11434/v1" }
Write-Host "Checking $base/models ..."
try {
    $r = Invoke-RestMethod -Uri "$base/models" -Method Get -TimeoutSec 15
    $r.models | ForEach-Object { Write-Host "  model:" $_.name }
} catch {
    Write-Error "Ollama not reachable at $base — install Ollama and run: ollama pull qwen2.5:3b"
    exit 1
}
Write-Host "OK"
