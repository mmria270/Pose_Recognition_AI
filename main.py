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
        "poses": [
            "Both Hands Up",
            "Squat",
            "T-Pose",
            "Lunge Left",
            "Lunge Right",
            "Balance Left Leg",
            "Balance Right Leg",
            "Bow",
            "Hands on Hips",
            "Side Raise Left",
            "Touch Shoulders",
            "High Knee Left",
            "High Knee Right",
            "Streamline Pose"
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



#  uvicorn main:app --reload --host 0.0.0.0