#!/usr/bin/env bash
# Runs every AI module's integration tests against several local Ollama models and collects the raw logs.
# Deliberately bypasses the Testcontainer of docs/adr/0002 (OLLAMA_TESTCONTAINER=false): that container
# bakes in one model, while a benchmark has to pick the model, so this drives a local Ollama daemon.
#
# The logs carry everything the comparison needs: TestNameLoggingExtension writes OK/FAIL/SKIP per test,
# TokenUsageAdvisor writes tokens and milliseconds per query plus a summary per test class. This script
# only orchestrates and collects - parsing and summarizing the logs is a separate, later step.
#
# Beware when reading the results: all four IT classes carry @Timeout(300s). A weak model on CPU can
# blow that in a tool-calling variant, which then looks like a wrong answer. The FAIL line tells them apart.
#
# Usage:
#   OLLAMA_MODELS=qwen3:8b,llama3.2:3b ./benchmark/benchmark.sh
#
#   OLLAMA_MODELS    comma-separated model names, exactly as "ollama list" prints them (required)
#   OLLAMA_BASE_URL  the Ollama daemon to measure against (default http://localhost:11434)
#   BENCHMARK_RUNS   repetitions per model and variant (default 3)

# No "-e": a failing test run is a measurement, not an error, and must not end the benchmark.
set -uo pipefail

BASE_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"
RUNS="${BENCHMARK_RUNS:-3}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# One line per variant: label, Maven module, IT class. The four variants of docs/canonical-query-set.md.
VARIANTS=(
    "02a 02-ai-agent-filter      FlatCustomerSearchIT"
    "02b 02-ai-agent-filter      OperatorCustomerSearchIT"
    "03  03-ai-structured-filter StructuredCustomerSearchIT"
    "04  04-ai-hybrid-filter     HybridCustomerSearchIT"
)

die() {
    echo "benchmark: $*" >&2
    exit 1
}

# Loads the model's weights and pins them in memory; no prompt, so nothing is generated.
pin_model() {
    curl -fsS --max-time 900 "${BASE_URL}/api/generate" \
        -d "{\"model\":\"$1\",\"keep_alive\":\"30m\"}" >/dev/null \
        || die "could not load model '$1'"
}

# Frees the weights again, so that never two models are resident and compete for RAM.
unload_model() {
    curl -fsS --max-time 60 "${BASE_URL}/api/generate" \
        -d "{\"model\":\"$1\",\"keep_alive\":0}" >/dev/null || true
}

# ":" and "/" in model names are legal but make for awkward file names.
safe_name() {
    local name="${1//:/-}"
    echo "${name//\//_}"
}

[[ -n "${OLLAMA_MODELS:-}" ]] \
    || die "OLLAMA_MODELS is required, e.g. OLLAMA_MODELS=qwen3:8b,llama3.2:3b $0"

# Spaces around the commas are the obvious way to write the list, so tolerate them.
IFS=',' read -r -a MODELS <<< "${OLLAMA_MODELS// /}"

# Check up front - otherwise a typo in a model name only surfaces hours later, as a Spring AI error.
VERSION="$(curl -fsS --max-time 10 "${BASE_URL}/api/version")" \
    || die "no Ollama at ${BASE_URL} - start it or set OLLAMA_BASE_URL"
TAGS="$(curl -fsS --max-time 10 "${BASE_URL}/api/tags")" \
    || die "could not read the model list from ${BASE_URL}/api/tags"
for model in "${MODELS[@]}"; do
    grep -qF "\"name\":\"${model}\"" <<< "${TAGS}" \
        || grep -qF "\"name\":\"${model}:latest\"" <<< "${TAGS}" \
        || die "model '${model}' is not installed - run: ollama pull ${model}"
done

RESULT_DIR="${REPO_ROOT}/benchmark/results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "${RESULT_DIR}"
INFO_FILE="${RESULT_DIR}/run-info.txt"

# The context a later reader of the logs cannot reconstruct: which code, which model quantization.
{
    # Spelled out rather than "date -Iseconds": that flag is GNU-only, and this runs on macOS too.
    echo "started:      $(date +%Y-%m-%dT%H:%M:%S%z)"
    echo "git commit:   $(git -C "${REPO_ROOT}" rev-parse HEAD)"
    echo "git branch:   $(git -C "${REPO_ROOT}" rev-parse --abbrev-ref HEAD)"
    echo "base url:     ${BASE_URL}"
    echo "models:       ${OLLAMA_MODELS}"
    echo "runs:         ${RUNS}"
    echo "ollama:       ${VERSION}"
    echo "installed:    ${TAGS}"
    echo
} > "${INFO_FILE}"

echo "benchmark: writing to ${RESULT_DIR}"

# Model outermost: this way each model is loaded once instead of once per variant.
for model in "${MODELS[@]}"; do
    echo "benchmark: loading ${model}"
    pin_model "${model}"

    for variant in "${VARIANTS[@]}"; do
        read -r label module it_class <<< "${variant}"

        for run in $(seq 1 "${RUNS}"); do
            log_file="${RESULT_DIR}/$(safe_name "${model}")__${label}__run${run}.log"
            echo "benchmark: ${model} / ${label} / run ${run}"

            # Renew the pin: Ollama's keep_alive default is five minutes, and between two Maven
            # invocations lie a Maven start, a 00-commons build and a Spring context boot.
            pin_model "${model}"

            started="$(date +%s)"
            OLLAMA_TESTCONTAINER=false \
            OLLAMA_BASE_URL="${BASE_URL}" \
            OLLAMA_MODEL="${model}" \
                "${REPO_ROOT}/mvnw" -f "${REPO_ROOT}/pom.xml" verify \
                    -pl "${module}" -am -B --no-transfer-progress \
                    -Dit.test="${it_class}" > "${log_file}" 2>&1
            exit_code=$?
            duration=$(( $(date +%s) - started ))

            # Failing tests are the measurement for a weaker model, so keep going either way.
            printf '%-24s %-4s run%-3s exit=%-3s %ss\n' \
                "${model}" "${label}" "${run}" "${exit_code}" "${duration}" | tee -a "${INFO_FILE}"
        done
    done

    unload_model "${model}"
done

echo "benchmark: done - ${RESULT_DIR}"
