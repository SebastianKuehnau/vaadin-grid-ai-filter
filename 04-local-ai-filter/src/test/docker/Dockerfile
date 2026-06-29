# Pre-bakes an Ollama model into the image, so the Testcontainers integration test needs neither a
# model pull nor a host bind-mount at test time. Build it once on your machine; the test then uses
# the local image directly.
#
# Build (default model qwen3.5:9b):
#   docker build -t ai-grid-filter/ollama:qwen3.5-9b 04-local-ai-filter/src/test/docker
#
# Build a different / smaller model:
#   docker build --build-arg OLLAMA_MODEL=llama3.2:1b \
#     -t ai-grid-filter/ollama:llama3.2-1b 04-local-ai-filter/src/test/docker
FROM ollama/ollama:latest

ARG OLLAMA_MODEL=qwen3.5:9b
ENV OLLAMA_MODEL=${OLLAMA_MODEL}

# Start the server, wait until it is ready, pull the model into this image layer, then stop it.
RUN set -eu; \
    ollama serve & srv=$!; \
    ready=0; \
    for i in $(seq 1 60); do if ollama list >/dev/null 2>&1; then ready=1; break; fi; sleep 1; done; \
    [ "$ready" = 1 ] || { echo "ollama server did not become ready"; exit 1; }; \
    ollama pull "$OLLAMA_MODEL"; \
    kill "$srv"; wait "$srv" 2>/dev/null || true

# The base image's ENTRYPOINT/CMD already run "ollama serve"; the model is now baked in.
