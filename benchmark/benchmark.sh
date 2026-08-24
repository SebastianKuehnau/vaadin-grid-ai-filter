#!/usr/bin/env bash
# Runs every AI module's integration tests against several local Ollama models and collects the raw logs.
# Deliberately bypasses the Testcontainer of docs/adr/0002 (OLLAMA_TESTCONTAINER=false): that container
# bakes in one model, while a benchmark has to pick the model, so this drives a local Ollama daemon.
#
# The logs carry everything the comparison needs: TestNameLoggingExtension writes OK/FAIL/SKIP per test,
# TokenUsageAdvisor writes tokens and milliseconds per query plus a summary per test class. No line of
# this script parses any of it - at the end Claude Code reads the logs and writes report.md, with the
# prompt of benchmark/report-prompt.md. That report step is allowed Bash so it can compute the medians
# with a throwaway script of its own instead of in its head; see docs/adr/0003. The logs stay the
# artifact: BENCHMARK_REPORT=false skips the summary, and a failed or skipped report never invalidates
# a run that took hours.
#
# Beware when reading the results: all four IT classes carry @Timeout(300s). A weak model on CPU can
# blow that in a tool-calling variant, which then looks like a wrong answer. The FAIL line tells them apart.
#
# Only ever one model may be resident: two 8B models are ~12 GB and make Ollama fit the second one
# around the first, which is measured as the second model being slow. The apps ask for keep-alive=1h,
# so a leftover outlives the run that loaded it - hence the cleanup at the start, at every model
# switch and in the EXIT trap, each waiting for the daemon to confirm rather than assuming it.
#
# Usage:
#   OLLAMA_MODELS=qwen3:8b,llama3.2:3b ./benchmark/benchmark.sh
#
#   OLLAMA_MODELS    comma-separated model names, exactly as "ollama list" prints them (required)
#   OLLAMA_BASE_URL  the Ollama daemon to measure against (default http://localhost:11434)
#   BENCHMARK_RUNS   repetitions per model and variant (default 3)
#   BENCHMARK_REPORT "false" leaves the logs unsummarized (default true, needs "claude" on PATH)

# No "-e": a failing test run is a measurement, not an error, and must not end the benchmark.
set -uo pipefail

BASE_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"
RUNS="${BENCHMARK_RUNS:-3}"
REPORT="${BENCHMARK_REPORT:-true}"
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

# Asks the daemon which models are resident - one name per line, nothing if it is idle.
resident_models() {
    curl -fsS --max-time 10 "${BASE_URL}/api/ps" \
        | tr ',' '\n' | sed -n 's/.*"name":"\([^"]*\)".*/\1/p'
}

# Loads the model's weights and pins them in memory; no prompt, so nothing is generated.
# num_ctx mirrors the three application-ollama.properties, so this warms the runner the tests then use.
pin_model() {
    curl -fsS --max-time 900 "${BASE_URL}/api/generate" \
        -d "{\"model\":\"$1\",\"keep_alive\":\"30m\",\"options\":{\"num_ctx\":4096}}" >/dev/null \
        || die "could not load model '$1'"
}

# Frees one model's weights again. A failure here skews every later measurement, so it is not silent.
unload_model() {
    curl -fsS --max-time 60 "${BASE_URL}/api/generate" \
        -d "{\"model\":\"$1\",\"keep_alive\":0}" >/dev/null \
        || echo "benchmark: warning - could not unload '$1'" >&2
}

# Ollama frees a model asynchronously: the unload request returns before the llama-server is gone.
# Loading the next model in that window leaves two of them in RAM, and Ollama then fits the new one
# to the memory the old one still holds - which is what makes the next model slow for its whole life.
await_daemon_idle() {
    local waited=0
    while [[ -n "$(resident_models)" ]] && [[ "${waited}" -lt 120 ]]; do
        sleep 1
        waited=$(( waited + 1 ))
    done
    [[ -z "$(resident_models)" ]] \
        || echo "benchmark: warning - still resident after ${waited}s: $(resident_models | tr '\n' ' ')" >&2
}

# Frees every resident model, not only the ones this run pinned: an aborted run or a finished demo
# leaves one pinned for an hour (the apps ask for keep-alive=1h) and it would skew every measurement.
unload_all_models() {
    local model
    for model in $(resident_models); do
        unload_model "${model}"
    done
    await_daemon_idle
}

# Has Claude Code read the logs and write report.md next to them, using benchmark/report-prompt.md.
# Runs after the last model is freed, so the report never competes with a model for RAM.
# Bash is allowed on purpose: the prompt asks for a throwaway parser, because medians over two dozen
# logs computed in the model's head are where silent errors come from (docs/adr/0003).
write_report() {
    local prompt_file="${REPO_ROOT}/benchmark/report-prompt.md"
    if [[ ! -f "${prompt_file}" ]]; then
        echo "benchmark: ${prompt_file} is missing - skipping the report" >&2
        return
    fi
    if ! command -v claude >/dev/null; then
        echo "benchmark: 'claude' is not on PATH - skipping the report, the logs are complete" >&2
        return
    fi

    # The prompt names the directory it is about; everything else is the same for every run.
    local prompt
    prompt="$(sed "s|<RESULT_DIR>|${RESULT_DIR#"${REPO_ROOT}/"}|g" "${prompt_file}")"

    echo "benchmark: writing report.md - this reads every log and takes a few minutes"
    # From the repository root, so the relative paths in the prompt resolve, and without stdin.
    ( cd "${REPO_ROOT}" && claude -p "${prompt}" \
        --allowedTools Read Write Glob Grep Bash < /dev/null ) \
        || echo "benchmark: the report failed - the logs in ${RESULT_DIR} are still complete" >&2
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

# Ctrl-C is the normal way out of an hours-long run; without this the current model stays pinned for
# an hour and the next benchmark measures against it. INT/TERM exit, which then runs the EXIT trap.
trap 'echo "benchmark: freeing models before exit"; unload_all_models' EXIT
trap 'exit 130' INT TERM

# Anything still loaded from an earlier run or a demo would compete for RAM with every measurement.
echo "benchmark: freeing models left over from earlier runs"
unload_all_models

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

            # Records what was loaded, so a measurement taken next to a second model is recognizable.
            printf 'resident  %-24s %-4s run%-3s %s\n' "${model}" "${label}" "${run}" \
                "$(resident_models | tr '\n' ' ')" >> "${INFO_FILE}"

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

    # Waits until the daemon is actually idle, so the next model is not fitted around this one's RAM.
    unload_all_models
done

if [[ "${REPORT}" == "true" ]]; then
    write_report
fi

echo "benchmark: done - ${RESULT_DIR}"
