# Bundled ML models

## lite-model_efficientdet_lite0_detection_metadata_1.tflite

EfficientDet-Lite0 object-detection model, **int8 quantized**, with metadata for
the LiteRT (TensorFlow Lite) Task Library `ObjectDetector`. Trained on the COCO
2017 label set (80 classes); LensCast's ML gate allows a subset of it
(person / pets / vehicles) through `DetectionClassPolicy`.

- **License:** Apache-2.0
- **Size:** ~4.4 MB (4,563,519 bytes) as shipped in the APK
- **Source:** https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite
  (published by the TensorFlow Hub "lite-model" collection; consumed through the
  task library at `org.tensorflow:tensorflow-lite-task-vision`).
- **Input:** 320x320 RGB (the task library resizes the decoded bitmap).
- **Consumer:** `capture/ml/ObjectDetectionEngine.kt` — loaded lazily from
  assets on first ML-gated motion event; the engine degrades to disabled (one
  rate-limited log line) when the asset is missing or fails to load, and the
  detection path fails open (motion alerts behave as if ML were off).
