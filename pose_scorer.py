"""
pose_scorer.py
后端调用入口: analyze_image(image_input, pose_name) -> dict
实时预览:     python pose_scorer.py
快捷键: A/D 切换动作, P 打印角度, ESC 退出
"""
import random

import cv2
import numpy as np
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
from mediapipe.tasks.python.vision import drawing_utils, drawing_styles
import urllib.request
import os
import time

# ── 模型初始化（模块级，全局单例）────────────────────────────────────────────

MODEL_PATH = 'pose_landmarker_full.task'

def _init_detector(num_poses=3):
    if not os.path.exists(MODEL_PATH):
        print("Downloading model...")
        urllib.request.urlretrieve(
            'https://storage.googleapis.com/mediapipe-models/pose_landmarker/'
            'pose_landmarker_full/float16/1/pose_landmarker_full.task',
            MODEL_PATH
        )
    return vision.PoseLandmarker.create_from_options(
        vision.PoseLandmarkerOptions(
            base_options=python.BaseOptions(model_asset_path=MODEL_PATH),
            num_poses=num_poses,
            output_segmentation_masks=False,
        )
    )

# 全局 detector，import 本模块时自动初始化
_detector = _init_detector()
_live_detector = _init_detector()

# ── 工具函数 ──────────────────────────────────────────────────────────────────

def calc_angle(a, b, c):
    a, b, c = np.array(a), np.array(b), np.array(c)
    ba, bc = a - b, c - b
    cos_val = np.dot(ba, bc) / (np.linalg.norm(ba) * np.linalg.norm(bc) + 1e-6)
    return float(np.degrees(np.arccos(np.clip(cos_val, -1.0, 1.0))))

def lm_xy(lm, idx):
    return (lm[idx].x, lm[idx].y)

def angle_score(measured, target, tol=25):
    diff = abs(measured - target)
    if diff <= tol:
        return 100 - (diff / tol) * 40
    else:
        return max(0.0, 60 - ((diff - tol) / tol) * 30)

# ── 动作定义 ──────────────────────────────────────────────────────────────────
#  MediaPipe 索引:
#  11=L_SHOULDER 12=R_SHOULDER 13=L_ELBOW  14=R_ELBOW
#  15=L_WRIST    16=R_WRIST    23=L_HIP    24=R_HIP
#  25=L_KNEE     26=R_KNEE     27=L_ANKLE  28=R_ANKLE  0=NOSE

POSES = {
    "Both Hands Up": [
        {"name": "L-Elbow",      "joints": (15,13,11), "target":170, "tol":25, "w":0.25},
        {"name": "R-Elbow",      "joints": (16,14,12), "target":170, "tol":25, "w":0.25},
        {"name": "L-Shoulder",   "joints": (13,11,23), "target":160, "tol":30, "w":0.25},
        {"name": "R-Shoulder",   "joints": (14,12,24), "target":160, "tol":30, "w":0.25},
    ],
    "Punch Right": [
            {"name": "R-Arm-Out",    "joints": (16,14,12), "target":170, "tol":20, "w":0.65},
            {"name": "R-Forward",    "joints": (14,12,24), "target": 75, "tol":25, "w":0.20},
            {"name": "L-Elbow-Pull", "joints": (11,13,15), "target": 60, "tol":25, "w":0.15},
    ],
    "Squat": [
        {"name": "L-Knee",       "joints": (23,25,27), "target": 95, "tol":35, "w":0.4},
        {"name": "R-Knee",       "joints": (24,26,28), "target": 95, "tol":35, "w":0.4},
        {"name": "Torso",        "joints": (11,23,25), "target":110, "tol":30, "w":0.2},
    ],
    "T-Pose": [
        {"name": "L-Arm",        "joints": (15,13,11), "target":175, "tol":15, "w":0.25},
        {"name": "R-Arm",        "joints": (16,14,12), "target":175, "tol":15, "w":0.25},
        {"name": "L-Shoulder",   "joints": (13,11,23), "target": 90, "tol":20, "w":0.25},
        {"name": "R-Shoulder",   "joints": (14,12,24), "target": 90, "tol":20, "w":0.25},
    ],
    "Lunge Right": [
        {"name": "R-Knee",       "joints": (24,26,28), "target": 90, "tol":35, "w":0.4},
        {"name": "L-Leg",        "joints": (23,25,27), "target":165, "tol":25, "w":0.4},
        {"name": "Torso",        "joints": (12,24,26), "target":165, "tol":25, "w":0.2},
    ],
    "Balance Right Leg": [
        {"name": "R-Leg",        "joints": (24,26,28), "target":175, "tol":20, "w":0.5},
        {"name": "L-Lift",       "joints": (23,25,27), "target": 85, "tol":30, "w":0.5},
    ],
    "Bow": [
        {"name": "Torso-Lean",   "joints": (12,11,23), "target": 80, "tol":25, "w":0.6},
        {"name": "Knees",        "joints": (23,25,27), "target":170, "tol":20, "w":0.4},
    ],
    "Hands on Hips": [
        {"name": "L-Elbow",      "joints": (15,13,11), "target": 90, "tol":30, "w":0.25},
        {"name": "R-Elbow",      "joints": (16,14,12), "target": 90, "tol":30, "w":0.25},
        {"name": "Torso",        "joints": (11,23,25), "target":175, "tol":15, "w":0.5},
    ],
    "Touch Shoulders": [
        {"name": "L-Elbow-Flex", "joints": (15,13,11), "target": 40, "tol":25, "w":0.5},
        {"name": "R-Elbow-Flex", "joints": (16,14,12), "target": 40, "tol":25, "w":0.5},
    ],
    "Heart Above Head": [
        # 双臂从髋往上举：髋→肩→肘角度大
        {"name": "L-Shoulder-Up", "joints": (23, 11, 13), "target": 160, "tol": 25, "w": 0.3},
        {"name": "R-Shoulder-Up", "joints": (24, 12, 14), "target": 160, "tol": 25, "w": 0.3},

        # 双肘弯曲，形成爱心弧度（不能伸直）
        {"name": "L-Elbow-Bend",  "joints": (11, 13, 15), "target": 115, "tol": 30, "w": 0.1},
        {"name": "R-Elbow-Bend",  "joints": (12, 14, 16), "target": 115, "tol": 30, "w": 0.3},

    ],
    "Side Bow Fist Pose": [
        # 双肘弯曲收到胸前（抱拳核心）
        {"name": "L-Elbow-Cross", "joints": (11, 13, 15), "target": 70,  "tol": 30, "w": 0.1},
        {"name": "R-Elbow-Cross", "joints": (12, 14, 16), "target": 70,  "tol": 30, "w": 0.40},

        # 膝盖微弯，重心下沉（耍帅感）
        {"name": "L-Knee-Soft",  "joints": (23, 25, 27), "target": 155, "tol": 20, "w": 0.25},
        {"name": "R-Knee-Soft",  "joints": (24, 26, 28), "target": 155, "tol": 20, "w": 0.25},
    ],
    "Buriburi Beam": [
        {"name": "L-Arm-Straight", "joints": (15, 13, 11), "target": 170, "tol": 20, "w": 0.35},
        {"name": "L-Shoulder-Up",  "joints": (13, 11, 23), "target": 130, "tol": 25, "w": 0.25},
        {"name": "R-Elbow-Bend",   "joints": (12, 14, 16), "target":  55, "tol": 25, "w": 0.25},
        {"name": "R-Shoulder-Up",  "joints": (14, 12, 24), "target": 120, "tol": 25, "w": 0.15},
    ],
    "Ultraman Beam": [
        {"name": "L-Elbow-Cross",  "joints": (11, 13, 15), "target":  65, "tol": 25, "w": 0.4},
        {"name": "R-Elbow-Cross",  "joints": (12, 14, 16), "target":  65, "tol": 25, "w": 0.5},
        {"name": "L-Arm-In",       "joints": (23, 11, 13), "target":  55, "tol": 25, "w": 0.05},
        {"name": "R-Arm-In",       "joints": (24, 12, 14), "target":  55, "tol": 25, "w": 0.05},
    ],
    "Cowboy Tip": [
        {"name": "R-Elbow-Bend",   "joints": (12, 14, 16), "target":  80, "tol": 25, "w": 0.30},
        {"name": "R-Shoulder-Up",  "joints": (14, 12, 24), "target": 100, "tol": 25, "w": 0.60},
        {"name": "R-Wrist-High",   "joints": (0,  12, 16), "target": 145, "tol": 30, "w": 0.05},
        {"name": "L-Elbow-Hip",    "joints": (15, 13, 11), "target":  90, "tol": 30, "w": 0.05},
    ],
}

POSE_KEYS = list(POSES.keys())

# ── 共享评分（预览和后端统一使用）─────────────────────────────────────────────

def _compute_joint_scores(lm, pose_key):
    """共享评分核心：tol×1.5 容差 + 随机加分，预览和接口一致"""
    total = 0.0
    joint_detail = []
    for cfg in POSES[pose_key]:
        a, b, c_idx = cfg["joints"]
        ang = calc_angle(lm_xy(lm, a), lm_xy(lm, b), lm_xy(lm, c_idx))
        s = angle_score(ang, cfg["target"], cfg["tol"] * 1.5)
        total += s * cfg["w"]
        joint_detail.append({
            "joint_name":   cfg["name"],
            "joint_points": list(cfg["joints"]),
            "actual_angle": round(ang, 1),
            "target_angle": cfg["target"],
            "tolerance":    cfg["tol"],
            "single_score": round(s, 1),
            "weight":       cfg["w"],
        })
    bonus = random.uniform(10, 20)
    total = min(100.0, total + bonus)
    return round(total, 1), joint_detail


def _score_pose(lm, pose_key):
    """预览用包装：从共享评分结果中提取元组供 _draw_panel 使用"""
    total, joint_detail = _compute_joint_scores(lm, pose_key)
    details = [(d["joint_name"], d["actual_angle"], d["target_angle"], d["single_score"])
               for d in joint_detail]
    return total, details

# ── 后端入口 ──────────────────────────────────────────────────────────────────

def analyze_image(image_input, pose_name: str) -> dict:
    """
    后端调用入口。

    参数
    ----
    image_input : bytes 或 numpy.ndarray (BGR)
        - bytes  → 后端从请求体读到的原始图片字节流
        - ndarray → 已用 cv2.imread / cv2.imdecode 读取的 BGR 图
    pose_name : str
        POSES 中的 key，如 "Squat"、"T-Pose" 等

    返回
    ----
    {
      "code": 200,          # 200=成功, 201=未检测到人, 400=动作名不存在
      "msg": "success",
      "data": {
        "pose_name": str,
        "total_score": float,   # 0~100
        "has_human": bool,
        "landmarks": [...],     # 33个关键点 {index, x, y, z}
        "joint_detail": [...]   # 各角度 {joint_name, actual_angle, target_angle, ...}
      }
    }
    """
    if pose_name not in POSES:
        return {"code": 400, "msg": f"unknown pose: {pose_name}", "data": {}}

    # 支持 bytes 和 numpy array 两种输入
    if isinstance(image_input, (bytes, bytearray)):
        nparr = np.frombuffer(image_input, np.uint8)
        bgr = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    else:
        bgr = image_input

    rgb    = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    mp_img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
    result = _detector.detect(mp_img)

    if not result.pose_landmarks:
        return {"code": 201, "msg": "no person detected",
                "data": {"pose_name": pose_name, "total_score": 0,
                         "has_human": False, "landmarks": [], "joint_detail": []}}

    lm          = result.pose_landmarks[0]
    total_score, joint_detail = _compute_joint_scores(lm, pose_name)

    landmarks_all = [
        {"index": i, "x": round(lm[i].x,4), "y": round(lm[i].y,4), "z": round(lm[i].z,4)}
        for i in range(33)
    ]

    return {
        "code": 200,
        "msg":  "success",
        "data": {
            "pose_name":   pose_name,
            "total_score": total_score,
            "has_human":   True,
            "landmarks":   landmarks_all,
            "joint_detail": joint_detail,
        }
    }

# ── 实时预览 ──────────────────────────────────────────────────────────────────

def _draw_panel(frame, pose_key, score, details, fps):
    h, w = frame.shape[:2]
    pw   = 300
    panel = np.full((h, pw, 3), 18, dtype=np.uint8)

    def txt(s, x, y, scale=0.45, color=(210,210,210), thick=1):
        cv2.putText(panel, s, (x,y), cv2.FONT_HERSHEY_SIMPLEX, scale, color, thick, cv2.LINE_AA)

    txt(f"Pose: {pose_key}", 8, 22, 0.40, (180,180,255))

    sc  = int(score)
    col = (0,220,100) if sc>=80 else (0,200,255) if sc>=50 else (60,80,255)
    txt(f"Score: {sc}", 8, 52, 0.70, col, 2)

    bar = int((sc/100)*(pw-16))
    cv2.rectangle(panel, (8,62), (8+bar,74), col, -1)
    cv2.rectangle(panel, (8,62), (pw-8, 74), (70,70,70), 1)

    y = 94
    for name, measured, target, s in details:
        ok = abs(measured - target) < target * 0.15
        c2 = (0,200,120) if ok else (80,80,200)
        txt(f"{name:14s} {measured:5.1f}/{target}  [{int(s)}]", 8, y, 0.38, c2)
        y += 17

    txt(f"FPS: {int(fps)}",            8, h-50, 0.45, (100,200,100))
    txt("A/D: prev/next pose",         8, h-30, 0.35, (100,100,100))
    txt("P: print angles  ESC: quit",  8, h-14, 0.35, (100,100,100))

    return np.hstack([frame, panel])


def main():
    cap      = cv2.VideoCapture(0)
    pose_idx = 0
    prev_t   = time.time()
    print("Started. A/D = prev/next pose, P = print angles, ESC = quit")
    print(f"Current pose: {POSE_KEYS[pose_idx]}\n")

    while cap.isOpened():
        ret, frame = cap.read()
        if not ret:
            break
        frame = cv2.flip(frame, 1)

        rgb    = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        mp_img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        result = _detector.detect(mp_img)

        curr_t = time.time()
        fps    = 1 / (curr_t - prev_t + 1e-6)
        prev_t = curr_t

        pose_key      = POSE_KEYS[pose_idx]
        score, details = 0.0, []

        if result.pose_landmarks:
            style = drawing_styles.get_default_pose_landmarks_style()
            for lm_list in result.pose_landmarks:
                drawing_utils.draw_landmarks(
                    image=rgb,
                    landmark_list=lm_list,
                    connections=vision.PoseLandmarksConnections.POSE_LANDMARKS,
                    landmark_drawing_spec=style,
                )
            frame = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
            score, details = _score_pose(result.pose_landmarks[0], pose_key)
        else:
            cv2.putText(frame, "No person detected", (10,60),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0,0,255), 2)

        combined = _draw_panel(frame, pose_key, score, details, fps)
        cv2.namedWindow("Pose Test", cv2.WINDOW_NORMAL)
        cv2.imshow("Pose Test", combined)

        key = cv2.waitKey(1) & 0xFF
        if key == 27:
            break
        elif key in (ord('d'), ord('D'), 83):
            pose_idx = (pose_idx + 1) % len(POSE_KEYS)
            print(f">>> {POSE_KEYS[pose_idx]}")
        elif key in (ord('a'), ord('A'), 81):
            pose_idx = (pose_idx - 1) % len(POSE_KEYS)
            print(f"<<< {POSE_KEYS[pose_idx]}")
        elif key in (ord('p'), ord('P')):
            if result.pose_landmarks:
                lm = result.pose_landmarks[0]
                print(f"\n--- [{pose_key}] live angles ---")
                for c in POSES[pose_key]:
                    a, b, cc = c["joints"]
                    ang = calc_angle(lm_xy(lm,a), lm_xy(lm,b), lm_xy(lm,cc))
                    print(f"  {c['name']:16s}: {ang:.1f}  (target={c['target']})")
                print()

    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
