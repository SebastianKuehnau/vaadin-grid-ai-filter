# Ollama with the demo model baked in, so a sandbox never waits on a 5 GB download.
FROM ollama/ollama
RUN (ollama serve &) \
 && until ollama list >/dev/null 2>&1; do sleep 1; done \
 && ollama pull qwen3:8b
