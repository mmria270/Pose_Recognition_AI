from fastapi import FastAPI, UploadFile, File, Request, Form
from fastapi.middleware.cors import CORSMiddleware
from pose_scorer import analyze_image

# 必须存在这行，uvicorn才能识别app
app = FastAPI(title="AI姿态闯关后端")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/pose/list")
def get_pose_list():
    return {
        "poses" : [
    "Both Hands Up",
    "Punch Right",
    "Squat",
    "T-Pose",
    "Lunge Right",
    "Balance Right Leg",
    "Bow",
    "Hands on Hips",
    "Touch Shoulders",
    "Heart Above Head",
    "Side Bow Fist Pose",
    "Buriburi Beam",
    "Ultraman Beam",
    "Cowboy Tip"
]
    }




@app.post("/pose/check")
async def check_pose(
    request: Request,
    image: UploadFile = File(...),
    pose_name: str = Form("")
):
    form_data = await request.form()
    print("收到的全部表单key:", list(form_data.keys()))
    print("pose_name参数:", pose_name)

    image_bytes = await image.read()
    res = analyze_image(image_bytes, pose_name)
    print("本次打分结果：", res)
    return res

# 新增骨骼关键点接口
@app.post("/pose/draw_joints")
async def draw_joints(image: UploadFile = File(...)):
    import numpy as np
    import cv2
    import mediapipe as mp
    from pose_scorer import _detector

    image_bytes = await image.read()
    nparr = np.frombuffer(image_bytes, np.uint8)
    bgr = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    mp_img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)

    result = _detector.detect(mp_img)

    if not result.pose_landmarks:
        return {"code": 201, "msg": "no person", "joints": []}

    lm = result.pose_landmarks[0]
    joints = [
        {"x": round(lm[i].x, 4), "y": round(lm[i].y, 4)}
        for i in range(33)
    ]

    return {"code": 200, "joints": joints}

#  uvicorn main:app --reload --host 0.0.0.0