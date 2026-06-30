#!/usr/bin/env python3
"""Step 2: Dynamic INT8 export from FP32 ONNX."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FP32 = ROOT / "onnx" / "model.onnx"
OUT = ROOT / "onnx" / "model_int8_dynamic.onnx"


def main() -> int:
    if not FP32.exists():
        print(f"Missing {FP32}", file=sys.stderr)
        return 1
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic
    except ImportError:
        print("Install: pip install onnxruntime", file=sys.stderr)
        return 1

    print(f"Quantizing {FP32} -> {OUT}")
    quantize_dynamic(
        str(FP32),
        str(OUT),
        weight_type=QuantType.QInt8,
    )
    print(f"Done: {OUT.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
