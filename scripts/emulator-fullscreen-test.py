"""Assert actual static video survives Surface replacement, then voice and stop."""
import json
import runpy
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.stdout.reconfigure(encoding="utf-8")
m = runpy.run_path(str(ROOT / "scripts/emulator-mvp-test.py"))
o, v, log = m["owner"], m["viewer"], m["log"]


def run():
    v.top()
    v.tap("全屏查看", scroll=True)
    v.find("退出全屏")
    m["assert_fullscreen_picture"]("viewer-fullscreen.png")
    v.shell("settings", "put", "system", "accelerometer_rotation", "0")
    try:
        v.shell("settings", "put", "system", "user_rotation", "1")
        v.find("退出全屏")
        m["assert_fullscreen_picture"]("viewer-fullscreen-landscape.png")
    finally:
        v.shell("settings", "put", "system", "user_rotation", "0")
    v.find("退出全屏")
    m["assert_fullscreen_picture"]("viewer-fullscreen-return.png")
    v.tap("退出全屏")
    log("PASS: actual static shared Settings picture is visible in fullscreen, landscape and portrait return")
    voice = runpy.run_path(str(ROOT / "scripts/emulator-voice-test.py"))
    voice["run"]()
    v.tap("断开并结束本次协助", scroll=True)
    m["stopped"]()
    (ROOT / "build/emulator-test/fullscreen-result.json").write_text(json.dumps({
        "staticPicture": True, "portrait": True, "landscape": True,
        "portraitReturn": True, "cleanup": True, "audioAfterRenderFix": True,
    }, indent=2), encoding="utf-8")


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        log(f"FAIL: {type(error).__name__}: {error}")
        sys.exit(1)
