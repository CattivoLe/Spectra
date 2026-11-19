#!/usr/bin/env python3
"""Scores assistant models on Spectra's own scenarios.

Scenarios come from android/app/src/test/.../AssistantEvalFixtures.kt (the app's exact system prompt, app-data block
and tool results). Each model is served by llama.cpp's llama-server (same version as the apps), every scenario is
asked several times with the app's sampling settings, and each reply is checked: the right tool with the right
arguments, no call where none is wanted, the facts it must mention, nothing it must not say, the reply language.

    python3 -I run.py --server path/to/llama-server --scenarios scenarios.json --out results/ model1.gguf model2.gguf

Writes results/<model>.jsonl (every reply) and prints a table. No dependencies beyond the standard library.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

CALL_TAG = "<tool_call>"
# Tool-call shapes the app does not understand: a model's own native format, or JSON without the tag.
FOREIGN_CALLS = [
    r"<\|tool_call_start\|>", r"\[TOOL_CALLS\]", r"<function_call>", r"<\|python_tag\|>",
    r'^\s*```(?:json)?\s*\{\s*"tool"', r'^\s*\{\s*"tool"\s*:',
]


# --native: the same tools as OpenAI function definitions, rendered by each model's own chat template and parsed
# by llama-server, so every model calls tools in the format it was trained on.
def _tool(name, description, properties=None, required=()):
    return {"type": "function", "function": {
        "name": name, "description": description,
        "parameters": {"type": "object", "properties": properties or {}, "required": list(required)},
    }}


NATIVE_TOOLS = [
    _tool("scan_wifi", "Nearby Wi-Fi networks, channels, signal, security."),
    _tool("scan_lan", "Devices on the local network, their ports and makers."),
    _tool("scan_bluetooth", "Nearby Bluetooth devices and trackers (AirTag)."),
    _tool("check_connection", "Internet check: DNS, latency, public IP, provider, access to a site or service, TLS interception."),
    _tool("speed_test", "Download/upload speed, latency under load, jitter."),
    _tool("check_port_forwarding", "Which ports the router forwards from the internet (UPnP/NAT-PMP). A rule nobody meant to add "
          "puts a device on the open internet. Needs the LAN hosts list: call scan_lan first when there is none."),
    _tool("scan_ports", "The usual open ports of one host from the LAN list.", {
        "host": {"type": "string", "description": "IP address from the LAN hosts list"},
        "text": {"type": "string", "enum": ["all"], "description": "\"all\" probes every TCP port (much slower); only when a full scan is asked for"},
    }, ["host"]),
    _tool("capture_pcap", "A few seconds of Wi-Fi traffic on one network's channel (needs the sensor).", {
        "bssid": {"type": "string", "description": "the network's BSSID exactly as in the Wi-Fi list"},
    }, ["bssid"]),
    _tool("connect_bluetooth", "Connect to ONE device from the Bluetooth list and read its GATT data (model, maker, firmware, battery). "
          "Call scan_bluetooth first when it is not listed.", {
              "text": {"type": "string", "description": "the device name exactly as in the Bluetooth list"},
          }, ["text"]),
    _tool("search_history", "Everything saved under History across sessions: was something seen before, what changed. Reads saved data only.", {
        "text": {"type": "string", "description": "a network or device name, IP, MAC or BSSID; leave out for an overview"},
    }),
    _tool("remember", "Save a short fact to the dossier, a notebook shared across chats.", {
        "text": {"type": "string", "description": "the note"},
        "host": {"type": "string", "description": "IP, MAC or BSSID of the one device the note is about, so it comes back with it"},
    }, ["text"]),
    _tool("forget", "Remove a note from the dossier.", {
        "text": {"type": "string", "description": "words from the note"},
    }, ["text"]),
]

NATIVE_GUIDANCE = """Your tools run live checks on this phone; you then get the result to answer with.
When the user asks to scan, check, test or measure anything about their own network, devices or connection, in any language («просканируй блютуз», «проверь интернет», «замерь скорость», «проверь доступ к гуглу»), call the matching tool even if older data is attached: a web search cannot measure anything. Otherwise call one only when fresher data is needed, and not again when its result is already there. Never report a check whose result you did not get.
When the attached data already holds what the user asks to explain, explain it instead of calling a tool again. A GATT read: the standard services, what the values reveal, vendor services that accept writes without pairing. A traffic capture: protocols, the busiest devices and pairs, anything unusual (deauthentication bursts, unknown devices, plain-text protocols). Recorded traffic of this phone: the servers and apps it talked to, plain HTTP, tracking or unexpected destinations.
The dossier in <app_data> is a notebook shared across chats. Save short facts worth keeping next time, or what the user asks to remember, with remember; give "host" (IP, MAC or BSSID) when the note is about one device."""

INSTRUCTIONS_START = "\n\nTools. To run a live check"
RESULT_TAG = re.compile(r'^<tool_result name="([^"]+)">\n(.*)\n</tool_result>', re.S)


def to_native(messages):
    """The app's text-format conversation, rewritten for native tool calling."""
    out, call_id = [], 0
    for message in messages:
        content = message["content"]
        if message["role"] == "system":
            out.append({"role": "system", "content": content.split(INSTRUCTIONS_START)[0] + "\n\n" + NATIVE_GUIDANCE})
        elif message["role"] == "assistant" and content.startswith(CALL_TAG):
            call = first_json_object(content) or {}
            name = call.pop("tool", "")
            call_id += 1
            out.append({"role": "assistant", "content": "", "tool_calls": [{
                "id": "call_%d" % call_id, "type": "function",
                "function": {"name": name, "arguments": json.dumps(call, ensure_ascii=False)},
            }]})
        elif message["role"] == "user" and RESULT_TAG.match(content):
            out.append({"role": "tool", "tool_call_id": "call_%d" % call_id, "content": RESULT_TAG.match(content).group(2)})
        else:
            out.append(message)
    return out


def native_reply(message):
    """A native reply in the text format score() reads: the first tool call becomes <tool_call>{...}."""
    content = message.get("content") or ""
    calls = message.get("tool_calls") or []
    if not calls:
        return content
    function = calls[0].get("function", {})
    try:
        args = json.loads(function.get("arguments") or "{}")
    except ValueError:
        args = {}
    if not isinstance(args, dict):
        args = {}
    return content + CALL_TAG + json.dumps(dict({"tool": function.get("name", "")}, **args), ensure_ascii=False)


def first_json_object(text):
    start = text.find("{")
    if start < 0:
        return None
    depth, in_string, escaped = 0, False, False
    for i in range(start, len(text)):
        c = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                in_string = False
        elif c == '"':
            in_string = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(text[start:i + 1])
                except ValueError:
                    return None
    return None


def parse_call(reply):
    """(call dict or None, visible text before the call, foreign-format flag)."""
    index = reply.find(CALL_TAG)
    if index >= 0:
        return first_json_object(reply[index + len(CALL_TAG):]) or {"tool": "<malformed>"}, reply[:index], False
    foreign = any(re.search(p, reply, re.M) for p in FOREIGN_CALLS)
    return None, reply, foreign


def cyrillic_share(text):
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return 0.0
    return sum(1 for c in letters if "Ѐ" <= c <= "ӿ") / len(letters)


def score(scenario, reply):
    expect = scenario["expect"]
    call, visible, foreign = parse_call(reply)
    problems = []
    if foreign:
        problems.append("call in a foreign format")
    if expect.get("tool"):
        if not call:
            problems.append("no call to " + expect["tool"])
        elif call.get("tool") != expect["tool"]:
            problems.append("called %s instead of %s" % (call.get("tool"), expect["tool"]))
        else:
            for key, wanted in expect.get("args", {}).items():
                value = str(call.get(key, ""))
                if wanted.lower() not in value.lower():
                    problems.append('%s=%r, wanted %r' % (key, value, wanted))
    else:
        if expect.get("noTool") and call:
            problems.append("unwanted call to %s" % call.get("tool"))
        lowered = visible.lower()
        for group in expect.get("include", []):
            if not any(word.lower() in lowered for word in group):
                problems.append("missing " + "/".join(group))
        for word in expect.get("exclude", []):
            if word.lower() in lowered:
                problems.append("says " + word)
        share = cyrillic_share(visible)
        if expect.get("lang") == "ru" and visible.strip() and share < 0.5:
            problems.append("not Russian (%.0f%% Cyrillic)" % (share * 100))
        if expect.get("lang") == "en" and share > 0.2:
            problems.append("not English (%.0f%% Cyrillic)" % (share * 100))
        if not call and not visible.strip():
            problems.append("empty answer")
    return problems, call


def post(port, body, timeout=600):
    request = urllib.request.Request(
        "http://127.0.0.1:%d/v1/chat/completions" % port, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read())


def wait_ready(port, process, seconds=180):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError("llama-server exited with %s" % process.returncode)
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/health" % port, timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("llama-server did not become ready")


def run_model(args, model, scenarios):
    name = os.path.basename(model).replace(".gguf", "") + (".native" if args.native else "")
    log = open(os.path.join(args.out, name + ".server.log"), "w")
    slots = args.parallel
    process = subprocess.Popen(
        [args.server, "-m", model, "--jinja", "-ngl", "99", "-c", str(8192 * slots), "-np", str(slots),
         "--port", str(args.port), "--no-webui", "--reasoning-budget", "0"],
        stdout=log, stderr=subprocess.STDOUT,
    )
    try:
        wait_ready(args.port, process)
        jobs = [(s, seed) for s in scenarios for seed in range(args.runs)]

        def ask(job):
            scenario, seed = job
            body = {
                "messages": to_native(scenario["messages"]) if args.native else scenario["messages"], "max_tokens": 1024, "temperature": 0.7, "top_p": 0.9, "top_k": 40,
                "seed": 1000 + seed, "chat_template_kwargs": {"enable_thinking": False},
            }
            if args.native:
                body["tools"] = NATIVE_TOOLS
            started = time.time()
            answer = post(args.port, body)
            message = answer["choices"][0]["message"]
            reply = native_reply(message) if args.native else (message.get("content") or "")
            problems, call = score(scenario, reply)
            return {
                "id": scenario["id"], "group": scenario["group"], "seed": seed, "ok": not problems,
                "problems": problems, "call": call, "tokens": answer.get("usage", {}).get("completion_tokens"),
                "finish": answer["choices"][0].get("finish_reason"), "seconds": round(time.time() - started, 1),
                "reply": reply,
            }

        with ThreadPoolExecutor(slots) as pool:
            results = list(pool.map(ask, jobs))
    finally:
        process.terminate()
        process.wait()
        log.close()
    with open(os.path.join(args.out, name + ".jsonl"), "w") as out:
        for row in results:
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
    return name, results


def summarize(name, results):
    groups = {}
    for row in results:
        groups.setdefault(row["group"], []).append(row["ok"])
    total = sum(r["ok"] for r in results)
    tokens = [r["tokens"] for r in results if r["tokens"] and not r["call"]]
    capped = sum(1 for r in results if r["finish"] == "length")
    print("\n== %s: %d/%d passed (%.0f%%), answers avg %d tokens, %d hit the 1024 cap" % (
        name, total, len(results), 100.0 * total / len(results), sum(tokens) / max(1, len(tokens)), capped))
    print("   " + "  ".join("%s %d/%d" % (g, sum(v), len(v)) for g, v in sorted(groups.items())))
    by_id = {}
    for row in results:
        by_id.setdefault(row["id"], []).append(row)
    for scenario_id, rows in by_id.items():
        failed = [r for r in rows if not r["ok"]]
        if failed:
            print("   ✗ %-18s %d/%d  %s" % (scenario_id, len(rows) - len(failed), len(rows), "; ".join(failed[0]["problems"])))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", required=True)
    parser.add_argument("--scenarios", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--parallel", type=int, default=3)
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--only", help="comma-separated scenario ids")
    parser.add_argument("--native", action="store_true", help="tools in each model's own format via its chat template")
    parser.add_argument("models", nargs="+")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    scenarios = json.load(open(args.scenarios))
    if args.only:
        wanted = set(args.only.split(","))
        scenarios = [s for s in scenarios if s["id"] in wanted]
    for model in args.models:
        name, results = run_model(args, model, scenarios)
        summarize(name, results)
        sys.stdout.flush()


if __name__ == "__main__":
    main()
