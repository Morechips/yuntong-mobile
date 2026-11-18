"""Fetch the exact upstream assets expected by the Android app and tests."""

import argparse
import hashlib
from pathlib import Path
import shutil
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
MODELS = (
    (
        "app/src/main/assets/efficientdet-lite0.tflite",
        "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite",
        "2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b",
    ),
    (
        "app/src/main/assets/vosk-model-small-cn-0.22.zip",
        "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
        "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba",
    ),
)
ANDROID_TEST_SAMPLE = (
    "app/src/androidTest/assets/cat_and_dog.jpg",
    "https://storage.googleapis.com/mediapipe-tasks/object_detector/cat_and_dog.jpg",
    "cfa90c34bb93021165e48bd22cfc20dbbb0440ff638a54878939bf30d362e824",
)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def prepare(relative_path, url, expected, verify_only):
    path = ROOT / relative_path
    if path.is_file() and sha256(path) == expected:
        print(f"Verified {relative_path}")
        return
    if verify_only:
        raise ValueError(f"Missing or mismatched asset: {relative_path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_suffix(path.suffix + ".part")
    print(f"Downloading {relative_path}", flush=True)
    try:
        with urlopen(url, timeout=90) as response, partial.open("wb") as stream:
            shutil.copyfileobj(response, stream)
        if sha256(partial) != expected:
            raise ValueError(f"SHA-256 mismatch: {url}")
        partial.replace(path)
    finally:
        partial.unlink(missing_ok=True)
    print(f"Verified {relative_path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-tests", action="store_true", help="also fetch the test photo")
    parser.add_argument("--verify-only", action="store_true", help="check local files without downloading")
    args = parser.parse_args()
    assets = MODELS + ((ANDROID_TEST_SAMPLE,) if args.android_tests else ())
    try:
        for asset in assets:
            prepare(*asset, verify_only=args.verify_only)
    except (OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
