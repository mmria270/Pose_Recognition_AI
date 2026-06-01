import cv2
import numpy as np
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
from mediapipe.tasks.python.vision import drawing_utils
from mediapipe.tasks.python.vision import drawing_styles
import urllib.request
import os
import torch
torch.set_num_threads(12)

# 下载模型文件
model_path = 'pose_landmarker_full.task'
if not os.path.exists(model_path):
    print("正在下载模型文件...")
    urllib.request.urlretrieve(
        'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task',
        model_path
    )
    print("下载完成")

# 画骨架的函数
def draw_landmarks_on_image(rgb_image, detection_result):
    annotated_image = np.copy(rgb_image)
    pose_landmark_style = drawing_styles.get_default_pose_landmarks_style()
    for pose_landmarks in detection_result.pose_landmarks:
        drawing_utils.draw_landmarks(
            image=annotated_image,
            landmark_list=pose_landmarks,
            connections=vision.PoseLandmarksConnections.POSE_LANDMARKS,
            landmark_drawing_spec=pose_landmark_style,
        )
    return annotated_image

# 初始化检测器
base_options = python.BaseOptions(model_asset_path=model_path)
options = vision.PoseLandmarkerOptions(
    base_options=base_options,
    num_poses=3,
    output_segmentation_masks=False
)
detector = vision.PoseLandmarker.create_from_options(options)

import time
prev_time = 0
cap = cv2.VideoCapture(0)
print("摄像头已启动，按ESC退出")

while cap.isOpened():
    ret, frame = cap.read()
    if not ret:
        break

    # BGR转RGB
    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)

    # 识别
    result = detector.detect(mp_image)

    # 画骨架
    if result.pose_landmarks:
        annotated = draw_landmarks_on_image(rgb, result)
        frame = cv2.cvtColor(annotated, cv2.COLOR_RGB2BGR)

        # 打印关键点
        lm = result.pose_landmarks[0]
        print(f"左肩: ({lm[11].x:.2f}, {lm[11].y:.2f}) | 右肩: ({lm[12].x:.2f}, {lm[12].y:.2f})")

    cv2.namedWindow('MediaPipe Pose', cv2.WINDOW_NORMAL)
    # 计算FPS
    curr_time = time.time()
    fps = 1 / (curr_time - prev_time)
    prev_time = curr_time

    # 把FPS画在画面左上角
    cv2.putText(frame, f'FPS: {int(fps)}', (10, 30),
                cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
    cv2.imshow('MediaPipe Pose', frame)
    if cv2.waitKey(1) == 27:
        break

cap.release()
cv2.destroyAllWindows()