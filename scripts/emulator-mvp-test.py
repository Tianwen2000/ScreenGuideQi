"""MVP lifecycle regression on two disposable emulators, following a live video test.

No real phone operations. Screenshots contain emulator Settings, not user content.
"""
import json
import runpy
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.stdout.reconfigure(encoding="utf-8")
m = runpy.run_path(str(ROOT / "scripts/emulator-smoke.py"))
Device, log, APP = m["Device"], m["log"], m["APP"]
owner, viewer = Device("emulator-5554"), Device("emulator-5556")
results = {"androidApi": 34, "realPhoneTest": False, "crossCarrierNatTest": False}


def assert_fullscreen_picture(name):
    from PIL import Image
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        path = viewer.screenshot(name)
        with Image.open(path) as image:
            crop = image.convert("L").crop((image.width // 10, 70, image.width * 9 // 10, image.height * 3 // 4))
            if sum(crop.histogram()[32:]) > 3000:
                return
        time.sleep(.5)
    raise RuntimeError("Fullscreen controls are visible but the static shared Settings picture is black")


def stopped():
    m["assert_capture_stopped"](owner, timeout=45)
    for device in (owner, viewer):
        if ".voice.VoiceService" in device.shell("dumpsys", "activity", "services", APP):
            raise RuntimeError("Microphone service remained active after session ended")


def new_session():
    owner.launch()
    viewer.launch()
    m["basic_run"](owner, viewer)


def run():
    viewer.top()
    viewer.tap("全屏查看", scroll=True)
    viewer.find("退出全屏")
    assert_fullscreen_picture("viewer-fullscreen.png")
    viewer.shell("settings", "put", "system", "accelerometer_rotation", "0")
    try:
        viewer.shell("settings", "put", "system", "user_rotation", "1")
        viewer.find("退出全屏")
        assert_fullscreen_picture("viewer-fullscreen-landscape.png")
    finally:
        viewer.shell("settings", "put", "system", "user_rotation", "0")
    viewer.find("退出全屏")
    viewer.shell("input", "keyevent", "4")
    viewer.find("全屏查看", scroll=True)
    results["fullscreenAndViewerRotation"] = True
    log("PASS: fullscreen renderer survives viewer rotation and Back returns to controls")

    # Locking either device must stop the entire session, without silent restart.
    voice = runpy.run_path(str(ROOT / "scripts/emulator-voice-test.py"))
    voice["enable"]()
    if ".voice.VoiceService" not in viewer.shell("dumpsys", "activity", "services", APP):
        raise RuntimeError("Microphone service did not start before lock test")
    viewer.shell("input", "keyevent", "223")
    stopped()
    audio_log = voice["app_logs"](viewer)
    if audio_log.count("record-start") > audio_log.count("record-stop"):
        raise RuntimeError("Android AudioRecord did not stop on lock")
    viewer.shell("input", "keyevent", "224")
    viewer.shell("wm", "dismiss-keyguard")
    results["viewerScreenOffEndsCapture"] = True
    log("PASS: viewer screen off ends screen capture and microphone")

    new_session()
    owner.shell("input", "keyevent", "223")
    stopped()
    owner.shell("input", "keyevent", "224")
    owner.shell("wm", "dismiss-keyguard")
    viewer.find("对方已结束会话", timeout=20, scroll=True)
    results["ownerScreenOffEndsCapture"] = True
    log("PASS: owner screen off ends both sides")

    new_session()
    viewer.shell("am", "force-stop", APP)
    stopped()
    results["viewerProcessDeathCleanup"] = True
    log("PASS: viewer process death ends owner capture")

    new_session()
    owner.shell("am", "force-stop", APP)
    stopped()
    viewer.find("对方已结束会话", timeout=45, scroll=True)
    results["ownerProcessDeathCleanup"] = True
    log("PASS: owner process death ends viewer session")

    new_session()
    started = time.monotonic()
    viewer.shell("svc", "wifi", "disable")
    try:
        stopped()
        results["mediaNetworkLossCleanupSeconds"] = round(time.monotonic() - started, 1)
    finally:
        viewer.shell("svc", "wifi", "enable")
        viewer.shell("am", "start", "-a", "android.settings.WIFI_SETTINGS")
        viewer.tap("AndroidWifi", timeout=15)
    time.sleep(3)
    m["assert_capture_stopped"](owner)
    results["noAutomaticCaptureRestart"] = True
    log("PASS: actual media network loss ends capture; network recovery does not restart sharing")
    (m["OUT"] / "mvp-result.json").write_text(json.dumps(results, indent=2), encoding="utf-8")


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        log(f"FAIL: {type(error).__name__}: {error}")
        sys.exit(1)
