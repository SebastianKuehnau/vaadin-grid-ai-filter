# Ollama with the demo model baked in, so a test run never waits on a 5 GB download.
# The model must stay the one the three application-ollama.properties name.
FROM ollama/ollama
RUN (ollama serve &) \
 && until ollama list >/dev/null 2>&1; do sleep 1; done \
 && ollama pull qwen3:8b
