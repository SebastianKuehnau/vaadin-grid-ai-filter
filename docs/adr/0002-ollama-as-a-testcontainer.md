# The ITs start their own Ollama as a Testcontainer

`OllamaContainerConfig` in `00-commons`' test-jar starts an Ollama container from
`00-commons/src/test/resources/ollama/Dockerfile`, which bakes `qwen3:8b` into the image, and Spring
AI's `@ServiceConnection` wires `spring.ai.ollama.base-url` to it. `@Import(OllamaContainerConfig.class)`
sits on the four service ITs and on `AbstractCustomerSearchViewIT`, which the four browserless ITs
inherit it from. Docker is the only prerequisite for `./mvnw verify`.

The obvious alternative is to provision an Ollama server next to the build — a service in the dev
environment, a container in the sandbox kit, an `ollama serve` on the developer's machine. **This
repository has tried that twice and reverted it twice** (`a27cd95` → `ab719f4` → `b37fb4a` → this).
Please do not swing the pendulum back a third time without reading the next section.

## Why

Provisioning is invisible work that every reader has to repeat. In the sandbox it lived in
`.sbx/kit/spec.yaml` as three install steps, an environment variable and five network allows —
and `spec.yaml` only takes effect when a sandbox is *created*, so an existing sandbox silently had no
Ollama at all and the ITs could not run in it. On a laptop it is a README instruction someone has to
follow. Neither can be executed by "clone the repository and run the tests", which is the bar this
repository is measured against: it exists to be picked up by strangers after a conference talk.

A Testcontainer moves that setup into the code that needs it, where it is version-controlled, visible
in one 30-line class, and identical on every machine with a Docker daemon.

## Consequences

- **Docker is now required** for `./mvnw verify`. `-DskipITs` still builds without one.
- **The container must be reused, or the ITs run out of RAM.** The IT classes have different Spring
  context configurations — `webEnvironment = NONE` for the service ITs, a full context plus a
  distinct `@ViewPackages` per browserless IT — so Spring caches several contexts per module JVM and
  never closes them: three in 02, two each in 03 and 04. Without reuse each context starts its own
  Ollama, each with its own resident `qwen3:8b` (`keep-alive=1h`), and 02 alone needs ~15 GB. The
  bean is therefore `withReuse(true)` and the three AI modules set `TESTCONTAINERS_REUSE_ENABLE=true`
  in their failsafe configuration, rather than relying on `~/.testcontainers.properties`, which every
  developer would have to create by hand — the exact setup step this decision is meant to abolish.
- **A container survives the build**, by design: the second run pays no model reload. Removing it is
  `docker rm -f $(docker ps -q --filter ancestor=ai-grid-filter/ollama:qwen3-8b)`.
- **The model name lives in four places** — the Dockerfile and the three `application-ollama.properties`.
  If they drift, the container serves one model while the app asks for another and Ollama silently
  tries to pull the difference. A Maven property with resource filtering would fix it and was
  rejected: the properties files are read from a slide, and `${ollama.model}` says less than `qwen3:8b`.
- **`OLLAMA_TESTCONTAINER=false`** skips the container, leaving the ITs on
  `spring.ai.ollama.base-url` (`${OLLAMA_BASE_URL:http://localhost:11434}`) — for a machine that
  already keeps `qwen3:8b` warm. The profile stays the answer to *which* backend; these two
  environment variables answer *where* it runs.
- **The sandbox kit no longer provisions anything for the tests.** What stayed in `spec.yaml` are the
  `registry.ollama.ai` and `*.r2.cloudflarestorage.com` network allows: the `ollama pull` still
  happens, just inside the Testcontainer's image build instead of at sandbox creation.
- **The base image is pinned by digest.** `FROM ollama/ollama@sha256:…` — with a floating `latest`,
  an upstream bump invalidates the cached layer and the 5 GB `ollama pull` runs again, unannounced.

## The sandbox takes the first escape hatch

Three things called "Ollama" appear in this repository, and the distinction is the whole point of
this section:

| Term | What it is |
|---|---|
| **Testcontainer-Ollama** | the `ai-grid-filter/ollama:qwen3-8b` image `OllamaContainerConfig` builds |
| **Host-Ollama** | an `ollama serve` on the development machine, reached via `OLLAMA_BASE_URL` |
| **Kit-Ollama** | a container started by `.sbx/kit/spec.yaml` at sandbox startup — **rejected, see below** |

In a `sbx` sandbox the Testcontainer does not fit: its Docker disk is **9.8 GB**, while
`ollama/ollama` needs ~7 GB and `qwen3:8b` another ~5.2 GB — ~12.2 GB. A run there does not fail
fast either; it downloads for ~25 minutes and then dies of ENOSPC. So `spec.yaml` sets
`OLLAMA_TESTCONTAINER=false` and `OLLAMA_BASE_URL=http://host.docker.internal:11434`: the sandbox
uses the **Host-Ollama**, which costs no bytes at sandbox creation. It needs `qwen3:8b` and one
network allow, `localhost:11434` — the name the sandbox proxy gives the host's loopback.

This is not the pendulum swinging back a third time. The two reverts above were about
*provisioning a model*, which every reader would have to repeat; the sandbox is one developer's
workbench, invisible to anyone who clones this repository, and it provisions nothing — it points at
a model that is already there. A **Kit-Ollama** was tried once more in `ba548d0` and reverted for
the same reason as before, plus a new one: it cannot even complete on that disk.

`./mvnw verify` on a development machine is unchanged — Testcontainer, one prerequisite, Docker.
