import cv2
import numpy as np
from collections import deque
from ultralytics import YOLO
import torch

from test_mediapipe import prev_time

torch.set_num_threads(12)
# ── 平衡配置 ─────────────────────────────────────────────────
model = YOLO('yolov8n-pose.pt')
IMGSZ      = 640                  # 默认分辨率，够用
CONF       = 0.4
IOU        = 0.65
SMOOTH_WIN = 5
SKIP_FRAME = 2                    # 每2帧推理一次，显示帧率翻倍

kp_history = [deque(maxlen=SMOOTH_WIN) for _ in range(17)]
KP_NAMES = [
    '鼻子','左眼','右眼','左耳','右耳',
    '左肩','右肩','左肘','右肘','左腕','右腕',
    '左髋','右髋','左膝','右膝','左踝','右踝'
]
import time
prev_time=0
cap = cv2.VideoCapture(0)
cap.set(cv2.CAP_PROP_FRAME_WIDTH,  1280)
cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)

frame_count = 0
last_annotated = None

while cap.isOpened():
    ret, frame = cap.read()
    if not ret:
        print("摄像头读取失败")
        break

    frame_count += 1

    # ── 跳帧：未推理的帧直接显示上一次结果 ──────────────────
    if frame_count % SKIP_FRAME != 0:
        if last_annotated is not None:
            cv2.imshow('YOLO Pose (ESC退出)', last_annotated)
        if cv2.waitKey(1) == 27:
            break
        continue

    results = model(frame, imgsz=IMGSZ, conf=CONF, iou=IOU, verbose=False)

    for r in results:
        if r.keypoints is None or len(r.keypoints.xy) == 0:
            continue

        kps = r.keypoints.xy[0].cpu().numpy()

        smoothed = []
        for i, pt in enumerate(kps):
            if pt[0] > 0 and pt[1] > 0:
                kp_history[i].append(pt)
            smoothed.append(np.mean(kp_history[i], axis=0) if kp_history[i] else pt)
        smoothed = np.array(smoothed)

        for idx in [5, 6, 7, 8, 9, 10]:
            x, y = smoothed[idx]
            print(f"{KP_NAMES[idx]}: ({x:.0f}, {y:.0f})", end="  ")
        print()

    last_annotated = results[0].plot()
    # 计算FPS
    curr_time = time.time()
    fps = 1 / (curr_time - prev_time)
    prev_time = curr_time

    # 把FPS画在画面左上角
    cv2.putText(frame, f'FPS: {int(fps)}', (10, 30),
                cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
    cv2.imshow('YOLO Pose (ESC退出)', last_annotated)
    if cv2.waitKey(1) == 27:
        break

cap.release()
cv2.destroyAllWindows()