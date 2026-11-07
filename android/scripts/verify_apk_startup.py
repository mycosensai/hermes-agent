"""Offline import/startup smoke using exact APK bytecode and requirements.
Run with Python 3.13: python3.13 -S scripts/verify_apk_startup.py APK.
Not an Android UI/native runtime test.
"""
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

assert sys.version_info[:2] == (3, 13), "Use bundled Python version 3.13"
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    with zipfile.ZipFile(sys.argv[1]) as apk:
        for name in ("requirements-common.imy", "app.imy"):
            with zipfile.ZipFile(io.BytesIO(apk.read("assets/chaquopy/" + name))) as archive:
                archive.extractall(root / "modules")
    code = r"""
import json, os, sys
sys.path.insert(0, sys.argv[1])
import httpx
# Startup must not make any network requests.
def denied(*args, **kwargs):
    raise AssertionError("Network access during offline startup")
httpx.Client = denied
httpx.AsyncClient = denied
import android_bridge as b
b.configure_home(sys.argv[2])
assert os.environ["HOME"] == sys.argv[2]
assert os.environ["HERMES_HOME"] == sys.argv[2]
assert json.loads(b.auth_status())["signed_in"] is False
assert json.loads(b.set_model("offline-test-model"))["ok"] is True
assert json.loads(b.set_api_key("offline-test-not-a-real-key"))["ok"] is True
assert json.loads(b.auth_status())["signed_in"] is True
b.clear_api_key()
assert json.loads(b.auth_status())["signed_in"] is False
print("PASS: exact APK Python imports, HOME configuration, model/key hydration, and offline auth status")
"""
    env = {k: v for k, v in os.environ.items() if k not in
           ("PYTHONPATH", "NOUS_API_KEY", "HERMES_API_KEY", "HERMES_HOME")}
    subprocess.run([sys.executable, "-S", "-c", code,
                    str(root / "modules"), str(root / "home")],
                   env=env, check=True)

    native_code = r"""
import json, os, sys, types
sys.path.insert(0, sys.argv[1])
class Native:
    calls = []
    turns = 0
    @staticmethod
    def request(url, method, headers, body):
        headers = json.loads(headers)
        assert headers.get("authorization") == "Bearer native-test-key"
        Native.calls.append((url, method))
        if url.endswith("/models"):
            data = {"data": [{"id": "openai/gpt-4.1"}, {"id": "cohere/embed-5-fast"}]}
        elif url.endswith("/chat/completions"):
            payload = json.loads(body)
            Native.turns += 1
            if Native.turns == 1:
                data = {"choices": [{"message": {"tool_calls": [{"id":"t1","function": {"name":"todo","arguments":json.dumps({"todos":[{"id":"1","content":"test","status":"pending"}]})}}]}}]}
            else:
                assert payload["messages"][-1]["role"] == "tool"
                data = {"choices": [{"message": {"content":"Native transport verified"}}]}
        else:
            raise AssertionError("Unexpected endpoint " + url)
        return json.dumps({"status":200,"body":json.dumps(data)})
java = types.ModuleType("java")
java.jclass = lambda name: Native
sys.modules["java"] = java
import android_bridge as b
b.configure_home(sys.argv[2])
from pathlib import Path
Path(sys.argv[2],"auth.json").write_text(json.dumps({"version":1,"providers":{"nous":{"agent_key":"native-test-key","access_token":"native-access"}}}))
assert json.loads(b.reload_oauth())["signed_in"]
models = json.loads(b.refresh_models(True))
assert models["ok"] and "openai/gpt-4.1" in models["models"] and "cohere/embed-5-fast" not in models["models"]
result = json.loads(b.chat("track a test"))
assert result["ok"] and result["reply"] == "Native transport verified"
assert result["via"] == "android_tool_loop"
assert Native.turns == 2
assert all(url.startswith("https://inference-api.nousresearch.com/v1/") for url, _ in Native.calls)
print("PASS: packaged Python native-HTTP adapter, OAuth rehydration, model fetch, and two-turn todo tool loop (mock transport)")
"""
    subprocess.run([sys.executable, "-S", "-c", native_code,
                    str(root / "modules"), str(root / "native-home")],
                   env=env, check=True)
