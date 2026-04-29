"""
Builds a tiny synthetic ONNX model for ChurnPredictorTest.

Shape contract (from ChurnPredictor):
  input  : float[1, 8]  named "input"
  logits : float[1, 1]  named "logits"  (sigmoid applied in Java)

Model: logits = Sum(input) — single MatMul with all-1 weights so the
output is simply the sum of the 8 features.  Trivial enough to predict
in tests:
  features = [0,0,0,0,0,0,0,0]      -> logit=0.0 -> sigmoid=0.5
  features = [4,0,0,0,0,0,0,0]      -> logit=4.0 -> sigmoid=0.982
  features = [-4,0,0,0,0,0,0,0]     -> logit=-4.0 -> sigmoid=0.018
"""
import sys
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper

input_tensor = helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 8])
output_tensor = helper.make_tensor_value_info("logits", TensorProto.FLOAT, [1, 1])

# Weight matrix [8, 1] all-ones — Sum(features)
W = numpy_helper.from_array(np.ones((8, 1), dtype=np.float32), name="W")

matmul_node = helper.make_node("MatMul", inputs=["input", "W"], outputs=["logits"])

graph = helper.make_graph(
    nodes=[matmul_node],
    name="ChurnPredictorTest",
    inputs=[input_tensor],
    outputs=[output_tensor],
    initializer=[W],
)

opset = helper.make_opsetid("", 18)
model = helper.make_model(graph, opset_imports=[opset], ir_version=9)
model.producer_name = "iris-test"

onnx.checker.check_model(model)

out_path = sys.argv[1] if len(sys.argv) > 1 else "churn-predictor-test.onnx"
onnx.save(model, out_path)
print(f"Wrote {out_path} ({len(model.SerializeToString())} bytes)")
