"""Check the delivered non-debug APK on a live default-WSS video session.

Normal emulator microphone input is silent; no synthetic probe or real host mic.
"""
import json
import re
import runpy
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.stdout.reconfigure(encoding="utf-8")
m = runpy.run_path(str(ROOT / "scripts/emulator-smoke.py"))
voice = runpy.run_path(str(ROOT / "scripts/emulator-voice-test.py"))
owner, viewer, log = voice["owner"], voice["viewer"], m["log"]


def run():
    try:
        viewer.shell("run-as", m["APP"], "id")
    except RuntimeError:
        pass
    else:
        raise RuntimeError("Expected the non-debuggable Release APK")
    voice["enable"]()
    owner.launch()
    owner.top()
    owner.find("指导端麦克风已开启", timeout=20, scroll=True)
    deadline = time.monotonic() + 20
    samples = 0
    while time.monotonic() < deadline:
        text = owner.find("语音统计：收到", contains=True, timeout=10, scroll=True)["text"]
        counts = re.search(r"收到 (\d+) 字节；采样 (\d+)", text)
        if counts and int(counts.group(1)) > 0 and int(counts.group(2)) > 0:
            samples = int(counts.group(2))
            break
    if samples == 0:
        raise RuntimeError("Release has no decoded inbound audio samples")
    log(f"PASS: actual Release microphone path sends audio; shared device decoded {samples} samples (silent emulator input)")
    viewer.tap("关闭麦克风", scroll=True)
    time.sleep(1)
    if ".voice.VoiceService" in viewer.shell("dumpsys", "activity", "services", m["APP"]):
        raise RuntimeError("Release microphone service remained after disabling voice")
    if m["APP"] not in owner.shell("dumpsys", "media_projection"):
        raise RuntimeError("Disabling voice unexpectedly stopped video")
    viewer.top()
    viewer.tap("全屏查看", scroll=True)
    viewer.find("退出全屏")
    fullscreen = runpy.run_path(str(ROOT / "scripts/emulator-mvp-test.py"))["assert_fullscreen_picture"]
    fullscreen("release-fullscreen.png")
    viewer.shell("settings", "put", "system", "accelerometer_rotation", "0")
    try:
        viewer.shell("settings", "put", "system", "user_rotation", "1")
        viewer.find("退出全屏")
        fullscreen("release-fullscreen-landscape.png")
    finally:
        viewer.shell("settings", "put", "system", "user_rotation", "0")
    viewer.find("退出全屏")
    log("PASS: static shared picture remains visible across fullscreen and viewer rotation")
    viewer.tap("退出全屏")
    viewer.top()
    viewer.find("视频统计：", contains=True, scroll=True)
    m["exercise"](owner, viewer, duration=30)
    viewer.tap("断开并结束本次协助", scroll=True)
    m["assert_capture_stopped"](owner)
    (m["OUT"] / "release-result.json").write_text(json.dumps({
        "version": "0.5.0-mvp", "buildType": "release", "androidApi": 34,
        "signal": "default public WSS", "mediaNetwork": "virtual Wi-Fi",
        "audioDecodedSamples": samples, "syntheticCapture": False,
        "hostMicrophoneUsed": False, "physicalMicrophoneTested": False,
        "videoContinuesAfterMicStop": True, "fullscreenStaticPicture": True, "viewerRotation": True, "cleanup": True,
        "realPhoneTest": False, "crossCarrierNatTest": False,
    }, indent=2), encoding="utf-8")


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        log(f"FAIL: {type(error).__name__}: {error}")
        sys.exit(1)
