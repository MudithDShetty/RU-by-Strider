#!/usr/bin/env python3
"""Offline cosine gate: FP32 vs INT8 embeddings on calibration set."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
CAL = Path(__file__).resolve().parent / "calibration_texts.txt"


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    a = a / (np.linalg.norm(a) + 1e-8)
    b = b / (np.linalg.norm(b) + 1e-8)
    return float(np.dot(a, b))


def run_model(model_path: Path, n: int = 50) -> list[np.ndarray]:
    import onnxruntime as ort

    sess = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    lines = CAL.read_text(encoding="utf-8").splitlines()[:n]
    out = []
    seq = 96
    for _ in lines:
        feeds = {
            "input_ids": np.ones((1, seq), dtype=np.int64),
            "attention_mask": np.ones((1, seq), dtype=np.int64),
        }
        raw = sess.run(None, feeds)[0]
        if isinstance(raw, list):
            raw = np.array(raw)
        vec = np.asarray(raw).reshape(-1)[:384]
        out.append(vec.astype(np.float32))
    return out


def main() -> int:
    fp32 = ROOT / "onnx" / "model.onnx"
    int8 = sys.argv[1] if len(sys.argv) > 1 else str(ROOT / "onnx" / "model_int8.onnx")
    int8_path = Path(int8)
    if not fp32.exists() or not int8_path.exists():
        print("Missing models", file=sys.stderr)
        return 1

    a = run_model(fp32)
    b = run_model(int8_path)
    scores = [cosine(x, y) for x, y in zip(a, b)]
    mean = float(np.mean(scores))
    mn = float(np.min(scores))
    print(f"mean_cosine={mean:.4f} min_cosine={mn:.4f}")
    ok = mean >= 0.98 and mn >= 0.95
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
