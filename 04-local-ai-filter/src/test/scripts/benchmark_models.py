#!/usr/bin/env python3
"""Compare local Ollama models for the natural-language -> CustomerFilter task.

Measures BOTH dimensions that matter for picking a model:
  - accuracy: does the model produce the expected filter criteria (exact field + operator + a value
    substring) for each query — the same checks as CustomerSearchIT?
  - speed:    median latency per request and generation tokens/second.

Docker-free: it talks to the Ollama server on localhost:11434 — point it at your NATIVE Ollama (GPU)
for realistic speed. Models must be available locally (run `ollama pull <model>` first). It reuses the
real system prompt from CustomerSearchService.java so the benchmark reflects production behaviour.

OpenAI models can be added with --openai <model> (e.g. gpt-4o-mini). Requires OPENAI_API_KEY env var.
Latency is wall-clock time; tokens/s is completion_tokens / latency_s (slightly pessimistic vs. Ollama
which uses pure eval time, so compare trends rather than absolute tok/s across backends).

Usage:
  python3 benchmark_models.py                                        # default Ollama model list
  python3 benchmark_models.py qwen3.5:9b qwen3.5:2b                  # compare specific Ollama models
  python3 benchmark_models.py --openai gpt-4o-mini                   # OpenAI only
  python3 benchmark_models.py qwen3.5:9b --openai gpt-4o-mini        # Ollama + OpenAI side-by-side
  python3 benchmark_models.py --markdown qwen3.5:9b                  # Markdown table (for slides)
  python3 benchmark_models.py --csv --runs 5 qwen3.5:9b              # CSV, 5 timed runs per query
  python3 benchmark_models.py --base-url http://host:11434 qwen3.5:9b
"""
import argparse
import datetime
import json
import os
import pathlib
import re
import statistics
import time
import urllib.request

DEFAULT_MODELS = ["qwen3.5:9b", "qwen3.5:4b", "qwen3.5:2b", "llama3.2:1b"]
TODAY = datetime.date.today().isoformat()  # sent to the model; relative-date cases are derived from this
_today = datetime.date.today()
_yesterday = (_today - datetime.timedelta(days=1)).isoformat()
_this_year_start = _today.replace(month=1, day=1).isoformat()
_last_month_start = (_today.replace(day=1) - datetime.timedelta(days=1)).replace(day=1).isoformat()
_last_week_start = (_today - datetime.timedelta(days=_today.weekday() + 7)).isoformat()

# Reuse the app's real system prompt so the benchmark reflects production behaviour.
SRC = (pathlib.Path(__file__).resolve().parents[2]
       / "main/java/dev/demo/vaadin/aigridfilter/ai/CustomerSearchService.java")
SYSTEM_PROMPT = (re.search(r'return\s+"""(.*?)"""\.formatted', SRC.read_text(), re.S)
                 .group(1).strip().replace("%s", TODAY)
                 + '\n\nRespond ONLY with a JSON object of this exact shape, nothing else:\n'
                   '{"criteria":[{"field":"...","operator":"...","value":"..."}]}')

# (query, expected) where expected is a list of (field, operator, value-substring), mirroring
# CustomerSearchIT.hasCriterion(field, operator, value). operator is an exact Operator name, a tuple of
# acceptable names, or None to ignore it. value-substring "" means "don't check the value" (used for
# relative dates, which the model computes from TODAY). An empty expected list means "show all".
CASES = [
    # --- mirrors CustomerSearchIT ---
    ("show me all customers in Berlin",
        [("city", ("EQUALS", "CONTAINS"), "berlin")]),
    ("show me all customers except from Berlin",
        [("city", ("NOT_EQUALS", "NOT_CONTAINS"), "berlin")]),
    ("customers in Berlin or Hamburg",
        [("city", "CONTAINS", "berlin"), ("city", "CONTAINS", "hamburg")]),
    ("show me all customers in Berlin or Hamburg with a minimal revenue of 100000",
        [("city", "CONTAINS", "berlin"), ("city", "CONTAINS", "hamburg"),
         ("annualRevenue", "GREATER_OR_EQUAL", "100000")]),
    ("show me all customers with an \"m\" as the first character in the contact name",
        [("contactName", "STARTS_WITH", "m")]),
    ("show me all customers with \"meyer\" in the contact name",
        [("contactName", ("EQUALS", "CONTAINS"), "meyer")]),
    ("show me all customers whose contact name ends with \"schmidt\"",
        [("contactName", "ENDS_WITH", "schmidt")]),
    ("customers whose contact name is Sofia and who are from Berlin",
        [("contactName", ("EQUALS", "CONTAINS"), "sofia"), ("city", ("EQUALS", "CONTAINS"), "berlin")]),
    ("show me the customer with the phone number 5020000001",
        [("phone", "CONTAINS", "5020000001")]),
    ("show me all customers who made an order yesterday",
        [("lastOrderDate", "EQUALS", _yesterday)]),

    # --- additional cases: cover every operator + more relative dates ---
    ("customers who became customers this year",
        [("customerSince", "GREATER_OR_EQUAL", _this_year_start)]),
    ("customers since 2020",
        [("customerSince", "GREATER_OR_EQUAL", "2020")]),
    ("customers whose last order was before 2024-01-01",
        [("lastOrderDate", "LESS_OR_EQUAL", ("2024-01-01", "2023-12-31"))]),
    ("companies with annual revenue over 1 million",
        [("annualRevenue", "GREATER_OR_EQUAL", "1000000")]),
    ("companies not in Munich with revenue between 100000 and 500000",
        [("city", ("NOT_EQUALS", "NOT_CONTAINS"), "munich"),
         ("annualRevenue", "GREATER_OR_EQUAL", "100000"), ("annualRevenue", "LESS_OR_EQUAL", "500000")]),
    ("customers in Germany",
        [("country", ("CONTAINS", "EQUALS"), "germany")]),
    ("customers whose email ends with .com",
        [("email", "ENDS_WITH", ".com")]),
    ("customers whose email does not contain gmail",
        [("email", ("NOT_CONTAINS", "NOT_EQUALS"), "gmail")]),
    ("customers whose company name starts with A",
        [("companyName", "STARTS_WITH", "a")]),

    # --- credit rating: a single rating, and several ratings that must stay SEPARATE criteria on
    #     the creditRating field (they are OR-combined; a single AND'd score range would be empty) ---
    ("creditworthy customers in Berlin",
        [("city", ("EQUALS", "CONTAINS"), "berlin"),
         ("creditRating", ("EQUALS", "CONTAINS"), ("good", "creditworthy"))]),
    ("show me all customers in Berlin with a good and an at-risk credit rating",
        [("city", ("EQUALS", "CONTAINS"), "berlin"),
         ("creditRating", ("EQUALS", "CONTAINS"), ("good", "creditworthy")),
         ("creditRating", ("EQUALS", "CONTAINS"), ("poor", "risk"))]),

    ("show all customers",
        []),
]


def chat(base_url, model, query):
    payload = {
        "model": model,
        "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                     {"role": "user", "content": query}],
        "think": False, "stream": False, "format": "json",
        # num_predict caps generation so a model that ignores format/think can't run until the timeout.
        "options": {"temperature": 0, "num_ctx": 4096, "num_predict": 512},
    }
    req = urllib.request.Request(base_url.rstrip("/") + "/api/chat",
                                 data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=300))


def chat_openai(model, query, api_key):
    payload = {
        "model": model,
        "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                     {"role": "user", "content": query}],
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "max_completion_tokens": 512,
    }
    req = urllib.request.Request(
        "https://api.openai.com/v1/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {api_key}"},
    )
    t0 = time.perf_counter()
    resp = json.load(urllib.request.urlopen(req, timeout=300))
    elapsed_s = time.perf_counter() - t0
    content = resp["choices"][0]["message"]["content"]
    completion_tokens = resp.get("usage", {}).get("completion_tokens", 0)
    return {
        "content": content,
        "latency_ms": elapsed_s * 1000,
        "tok_s": completion_tokens / elapsed_s if elapsed_s else 0,
    }


def ttft_ollama(base_url, model, query):
    """Time-to-first-token via streaming. Returns ms, or None on error."""
    payload = {
        "model": model,
        "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                     {"role": "user", "content": query}],
        "think": False, "stream": True, "format": "json",
        "options": {"temperature": 0, "num_ctx": 4096, "num_predict": 512},
    }
    req = urllib.request.Request(base_url.rstrip("/") + "/api/chat",
                                 data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            for line in resp:
                chunk = json.loads(line.decode())
                if not chunk.get("done") and chunk.get("message", {}).get("content"):
                    return (time.perf_counter() - t0) * 1000
    except Exception:
        return None
    return None


def ttft_openai(model, query, api_key):
    """Time-to-first-token via SSE streaming.

    Intentionally omits response_format=json_object: JSON mode causes OpenAI to buffer
    tokens until a valid JSON prefix is formed, which inflates TTFT and does not reflect
    the actual network + model startup latency the user perceives in the app.
    """
    payload = {
        "model": model,
        "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                     {"role": "user", "content": query}],
        "temperature": 0,
        "max_completion_tokens": 512,
        "stream": True,
    }
    req = urllib.request.Request(
        "https://api.openai.com/v1/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {api_key}"},
    )
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            for line in resp:
                line = line.decode().strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                chunk = json.loads(data)
                if chunk.get("choices", [{}])[0].get("delta", {}).get("content"):
                    return (time.perf_counter() - t0) * 1000
    except Exception:
        return None
    return None


def measure_ttft(fn):
    """Run fn(query) once per CASE, return median TTFT in ms (or None if all failed)."""
    ttfts = []
    for query, _ in CASES:
        t = fn(query)
        if t is not None:
            ttfts.append(t)
    return round(statistics.median(ttfts)) if ttfts else None


def criteria_of(content):
    """Best-effort: returns the list of criterion dicts the model produced, [] if the shape is off."""
    try:
        data = json.loads(content)
    except json.JSONDecodeError:
        m = re.search(r"\{.*\}|\[.*\]", content, re.S)
        try:
            data = json.loads(m.group(0)) if m else {}
        except json.JSONDecodeError:
            return []
    if isinstance(data, list):              # some models return a bare array
        crit = data
    elif isinstance(data, dict):
        crit = data.get("criteria", [])
    else:
        crit = []
    return crit if isinstance(crit, list) else []


def op_matches(actual, expected_op):
    """expected_op: None (ignore), an exact Operator name, or a tuple of acceptable names."""
    if expected_op is None:
        return True
    actual_upper = str(actual).upper()
    options = expected_op if isinstance(expected_op, (tuple, list)) else (expected_op,)
    return any(actual_upper == o.upper() for o in options)


def value_matches(actual, expected_value):
    """expected_value: a substring string, or a tuple of acceptable substrings (any one suffices)."""
    options = expected_value if isinstance(expected_value, (tuple, list)) else (expected_value,)
    actual_lower = str(actual).lower()
    return any(opt.lower() in actual_lower for opt in options)


def matches(criteria, field, operator, value_sub):
    """Like CustomerSearchIT.hasCriterion: exact field + operator, value matched as a substring."""
    for c in criteria:
        if not isinstance(c, dict):         # skip non-object entries (e.g. "city=Berlin" strings)
            continue
        if (str(c.get("field", "")).lower() == field.lower()
                and op_matches(c.get("operator", ""), operator)
                and value_matches(c.get("value", ""), value_sub)):
            return True
    return False


def case_correct(criteria, expected):
    if not expected:
        return len(criteria) == 0
    return all(matches(criteria, field, op, value) for field, op, value in expected)


def _collect_results(correct, latencies, throughputs, errors, failures, ttft_ms, wall_s):
    if not latencies:
        return {"error": "all requests failed/timed out"}
    return {
        "accuracy": f"{correct}/{len(CASES)}",
        "median_ms": round(statistics.median(latencies)),
        "ttft_ms": ttft_ms,
        "tok_s": round(statistics.median(throughputs), 1) if throughputs else 0,
        "wall_s": round(wall_s, 1),
        "errors": errors,
        "failures": failures,
    }


def benchmark(base_url, model, runs):
    wall_t0 = time.perf_counter()
    try:
        chat(base_url, model, "warm up")  # load the model so timings exclude the cold start
    except Exception as e:
        return {"error": str(e)}

    correct, errors = 0, 0
    latencies, throughputs = [], []
    failures = []
    for query, expected in CASES:
        case_latencies, content = [], ""
        for _ in range(runs):
            try:
                d = chat(base_url, model, query)
            except Exception:
                errors += 1   # timeout / transient error: skip this run, keep going
                continue
            content = d.get("message", {}).get("content", "")
            case_latencies.append(d.get("total_duration", 0) / 1e6)
            ev, ed = d.get("eval_count", 0), d.get("eval_duration", 0)
            if ed:
                throughputs.append(ev / (ed / 1e9))
        if case_latencies:
            latencies.append(statistics.median(case_latencies))
        criteria = criteria_of(content)
        if case_correct(criteria, expected):
            correct += 1
        else:
            failures.append({"query": query, "expected": expected, "got": criteria})

    ttft = measure_ttft(lambda q: ttft_ollama(base_url, model, q))
    wall_s = time.perf_counter() - wall_t0
    return _collect_results(correct, latencies, throughputs, errors, failures, ttft, wall_s)


def benchmark_openai(model, runs, api_key):
    wall_t0 = time.perf_counter()
    correct, errors = 0, 0
    latencies, throughputs = [], []
    failures = []
    for query, expected in CASES:
        case_latencies, content, case_toks = [], "", []
        for _ in range(runs):
            try:
                d = chat_openai(model, query, api_key)
            except Exception:
                errors += 1
                continue
            content = d["content"]
            case_latencies.append(d["latency_ms"])
            case_toks.append(d["tok_s"])
        if case_latencies:
            latencies.append(statistics.median(case_latencies))
            throughputs.append(statistics.median(case_toks))
        criteria = criteria_of(content)
        if case_correct(criteria, expected):
            correct += 1
        else:
            failures.append({"query": query, "expected": expected, "got": criteria})

    ttft = measure_ttft(lambda q: ttft_openai(model, q, api_key))
    wall_s = time.perf_counter() - wall_t0
    return _collect_results(correct, latencies, throughputs, errors, failures, ttft, wall_s)


def _ttft_str(r):
    t = r.get("ttft_ms")
    return f"{t} ms" if t is not None else "n/a"


def _wall_str(r):
    w = r.get("wall_s")
    return f"{w} s" if w is not None else "n/a"


def render(rows, fmt):
    cols = ("model", "accuracy", "median latency", "ttft", "tokens/s", "Wallclock")
    if fmt == "csv":
        print(",".join(("model", "accuracy", "median_ms", "ttft_ms", "tokens_s", "wallclock_s")))
        for m, r in rows:
            print(f"{m},{r.get('accuracy', 'ERROR')},{r.get('median_ms', '')},"
                  f"{r.get('ttft_ms', '')},{r.get('tok_s', '')},{r.get('wall_s', '')}")
    elif fmt == "markdown":
        print("| " + " | ".join(cols) + " |")
        print("|" + "|".join(["---"] * len(cols)) + "|")
        for m, r in rows:
            if "error" in r:
                print(f"| {m} | ERROR | | | | |")
            else:
                acc = r["accuracy"] + (f" ({r['errors']} err)" if r.get("errors") else "")
                print(f"| {m} | {acc} | {r['median_ms']} ms | {_ttft_str(r)} | {r['tok_s']} "
                      f"| {_wall_str(r)} |")
    else:
        print(f"{'model':<22}{'accuracy':<14}{'median latency':<16}{'ttft':<12}"
              f"{'tokens/s':<10}{'Wallclock':<12}")
        print("-" * 86)
        for m, r in rows:
            if "error" in r:
                print(f"{m:<22}ERROR: {r['error'][:30]} (try `ollama pull {m}`)")
            else:
                acc = r["accuracy"] + (f" ({r['errors']} err)" if r.get("errors") else "")
                print(f"{m:<22}{acc:<14}{str(r['median_ms']) + ' ms':<16}"
                      f"{_ttft_str(r):<12}{str(r['tok_s']):<10}{_wall_str(r):<12}")

        for m, r in rows:
            if "error" in r or not r.get("failures"):
                continue
            print(f"\nFailed cases for {m}:")
            for f in r["failures"]:
                exp_str = ", ".join(
                    f"{field}/{op}/{val}" for field, op, val in f["expected"]
                ) if f["expected"] else "(empty)"
                got_str = json.dumps(f["got"], ensure_ascii=False) if f["got"] else "(empty)"
                print(f"  FAIL  {f['query']!r}")
                print(f"        expected: {exp_str}")
                print(f"        got:      {got_str}")


def main():
    p = argparse.ArgumentParser(description="Compare Ollama and/or OpenAI models (accuracy + speed).")
    p.add_argument("models", nargs="*", help="Ollama models to compare (default list if none given)")
    p.add_argument("--base-url", default="http://localhost:11434", help="Ollama base URL")
    p.add_argument("--runs", type=int, default=3, help="timed runs per query (median is reported)")
    p.add_argument("--openai", metavar="MODEL", action="append", dest="openai_models",
                   help="add an OpenAI model (e.g. gpt-4o-mini); repeatable; needs OPENAI_API_KEY")
    fmt = p.add_mutually_exclusive_group()
    fmt.add_argument("--markdown", action="store_const", dest="fmt", const="markdown")
    fmt.add_argument("--csv", action="store_const", dest="fmt", const="csv")
    args = p.parse_args()

    ollama_models = args.models if args.models else ([] if args.openai_models else DEFAULT_MODELS)
    rows = [(m, benchmark(args.base_url, m, args.runs)) for m in ollama_models]

    if args.openai_models:
        api_key = os.environ.get("OPENAI_API_KEY", "")
        if not api_key:
            p.error("--openai requires the OPENAI_API_KEY environment variable to be set")
        for m in args.openai_models:
            rows.append((f"openai/{m}", benchmark_openai(m, args.runs, api_key)))

    render(rows, args.fmt)


if __name__ == "__main__":
    main()
