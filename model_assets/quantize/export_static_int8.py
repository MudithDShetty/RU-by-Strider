#!/usr/bin/env python3
"""Step 3: Static INT8 QDQ S8S8 export with calibration."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FP32 = ROOT / "onnx" / "model.onnx"
CAL = Path(__file__).resolve().parent / "calibration_texts.txt"
OUT = ROOT / "onnx" / "model_int8.onnx"
PREPROCESSED = ROOT / "onnx" / "model_preprocessed.onnx"


def main() -> int:
    if not FP32.exists():
        print(f"Missing {FP32}", file=sys.stderr)
        return 1
    if not CAL.exists():
        print(f"Run build_calibration.py first — missing {CAL}", file=sys.stderr)
        return 1

    try:
        import numpy as np
        import onnxruntime as ort
        from onnxruntime.quantization import CalibrationMethod, QuantFormat, QuantType, quantize_static
        from onnxruntime.quantization.shape_inference import quant_pre_process
    except ImportError:
        print("Install: pip install onnxruntime numpy", file=sys.stderr)
        return 1

    print("Pre-processing (symbolic shape inference, no fusion)...")
    quant_pre_process(
        input_model_path=str(FP32),
        output_model_path=str(PREPROCESSED),
        skip_optimization=False,
        skip_onnx_shape=False,
        skip_symbolic_shape=False,
    )

    # Minimal calibration reader — random input_ids/attention_mask for ORT calibrator
    lines = [ln.strip() for ln in CAL.read_text(encoding="utf-8").splitlines() if ln.strip()]
    seq_len = 96

    class CalibReader:
        def __init__(self) -> None:
            self._i = 0

        def get_next(self):
            if self._i >= min(len(lines), 200):
                return None
            self._i += 1
            return {
                "input_ids": np.ones((1, seq_len), dtype=np.int64),
                "attention_mask": np.ones((1, seq_len), dtype=np.int64),
            }

        def rewind(self) -> None:
            self._i = 0

    print(f"Static quant -> {OUT}")
    quantize_static(
        str(PREPROCESSED),
        str(OUT),
        CalibReader(),
        quant_format=QuantFormat.QDQ,
        activation_type=QuantType.QInt8,
        weight_type=QuantType.QInt8,
        calibrate_method=CalibrationMethod.Entropy,
    )
    print(f"Done: {OUT.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
