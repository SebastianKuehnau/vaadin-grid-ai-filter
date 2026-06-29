#!/usr/bin/env bash
# Pre-build one Ollama Testcontainers image PER model (model baked in -> no pull at test time).
# Run once before executing CustomerSearchServiceOllamaIT; the test then uses the local image.
#
# Usage:
#   ./build-images.sh                          # builds the default list below
#   ./build-images.sh qwen3.5:9b llama3.2:1b   # builds one image per given model
#
# Each model becomes its own image, tag derived from the model name (':' -> '-'):
#   qwen3.5:9b   -> ai-grid-filter/ollama:qwen3.5-9b
#   llama3.2:1b  -> ai-grid-filter/ollama:llama3.2-1b
set -euo pipefail

# Models built when no arguments are given.
DEFAULT_MODELS=("qwen3.5:9b" "qwen3.5:4b" "qwen3.5:2b")

MODELS=("$@")
[ ${#MODELS[@]} -eq 0 ] && MODELS=("${DEFAULT_MODELS[@]}")

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

tag_for() { echo "ai-grid-filter/ollama:$(echo "$1" | tr ':' '-')"; }

for MODEL in "${MODELS[@]}"; do
  TAG="$(tag_for "$MODEL")"
  echo "==> Building $TAG (model: $MODEL) — downloads the model once during the build…"
  docker build --build-arg "OLLAMA_MODEL=$MODEL" -t "$TAG" "$DIR"
  echo "==> Done: $TAG"
done

echo
echo "Built images:"
for MODEL in "${MODELS[@]}"; do echo "  $(tag_for "$MODEL")"; done
