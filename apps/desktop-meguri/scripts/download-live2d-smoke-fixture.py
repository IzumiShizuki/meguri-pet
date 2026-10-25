"""Download only official Haru 5-r.3 references into ignored isolated verification storage.

This sample is not bundled or selected for the user's pet. Its license remains
the Live2D Free Material License and model-specific terms linked by the SDK.
"""
import concurrent.futures
import json
import urllib.request
import time
from pathlib import Path

root = Path(__file__).resolve().parents[3] / ".codex-runtime" / "live2d-smoke" / "Haru"
base = "https://raw.githubusercontent.com/Live2D/CubismWebSamples/5-r.3/Samples/Resources/Haru/"
root.mkdir(parents=True, exist_ok=True)


def download(relative):
    target = root / relative
    if target.is_file():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(base + relative, headers={"User-Agent": "Meguri-isolated-runtime-verification"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                target.write_bytes(response.read())
            return
        except Exception:
            if attempt == 2:
                raise
            time.sleep(1)


download("Haru.model3.json")
document = json.loads((root / "Haru.model3.json").read_text())
refs = document["FileReferences"]
files = [refs["Moc"], *refs["Textures"]]
files.extend(refs[key] for key in ["Physics", "Pose", "DisplayInfo", "UserData"] if refs.get(key))
files.extend(expression["File"] for expression in refs.get("Expressions", []))
for group in refs.get("Motions", {}).values():
    for motion in group:
        files.append(motion["File"])
        if motion.get("Sound"):
            files.append(motion["Sound"])
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
    list(executor.map(download, files))
print(f"Authorized isolated fixture: {root} ({len(files) + 1} files)")
