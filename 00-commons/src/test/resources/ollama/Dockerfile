# Ollama with the demo model baked in, so a test run never waits on a 5 GB download.
# The model must stay the one the three application-ollama.properties name.
# Pinned by digest, so an upstream `latest` bump cannot invalidate the cached model layer.
FROM ollama/ollama@sha256:32931b46719f673c05fdbaa81ccb26da18ea4a1c57590a754874ab28ba269eb2
RUN (ollama serve &) \
 && until ollama list >/dev/null 2>&1; do sleep 1; done \
 && ollama pull qwen3:8b
