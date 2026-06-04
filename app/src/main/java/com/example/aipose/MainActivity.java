package com.example.aipose;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PosePose";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;

    // 后端接口地址
    private static final String BASE_URL = "http://10.133.225.220:8000";
    private static final String UPLOAD_URL = BASE_URL + "/pose/check";
    private static final String ACTION_LIST_URL = BASE_URL + "/pose/list";

    private PreviewView previewView;
    private TextView scoreText;
    private TextView actionText;
    private TextView uploadStatusText;
    private FloatingActionButton captureButton;
    private FloatingActionButton switchCameraButton;
    private FloatingActionButton nextActionButton;  // 新增：下一个动作按钮

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private OkHttpClient okHttpClient;

    // 摄像头选择：后置/前置
    private int cameraLensFacing = CameraSelector.LENS_FACING_BACK;

    // 动作列表
    private List<String> actionList = new ArrayList<>();
    private int currentActionIndex = 0;

    // 成员变量（添加到已有变量后面）
    private SkeletonOverlayView skeletonOverlayView;
    private ImageAnalysis imageAnalysis;         // ★ 补上缺失的声明
    private volatile boolean isAnalyzing = false; // ★ 实时帧传输锁
    private long lastAnalyzeTime = 0;             // ★ 上次分析时间戳

    private int passCount = 0;  // 累计通关次数
    private List<Sticker> unlockedStickers = new ArrayList<>();
    private Map<String, Bitmap> stickerBitmaps = new HashMap<>();
    private StickerManager stickerManager;
    // 贴纸配置（关卡数 → 贴纸）
    private static final Map<Integer, StickerConfig> STICKER_UNLOCK_CONFIG = new HashMap<>();
    static {
        // 通关2关解锁猫耳朵（贴在头上）
        STICKER_UNLOCK_CONFIG.put(2, new StickerConfig("cat_ears", "head"));
        // 通关4关解锁胡子（贴在鼻子附近）
        STICKER_UNLOCK_CONFIG.put(4, new StickerConfig("mustache", "nose"));
        // 通关6关解锁眼镜（贴在眼睛）
        STICKER_UNLOCK_CONFIG.put(6, new StickerConfig("glasses", "nose"));
        // 通关8关解锁皇冠（贴在头顶）
        STICKER_UNLOCK_CONFIG.put(8, new StickerConfig("crown", "head"));
        // 通关10关解锁爱心（贴在胸口）
        STICKER_UNLOCK_CONFIG.put(10, new StickerConfig("heart", "left_shoulder"));
    }
    static class StickerConfig {
        String name;
        String attachJoint;
        StickerConfig(String name, String attachJoint) {
            this.name = name;
            this.attachJoint = attachJoint;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        scoreText = findViewById(R.id.scoreText);
        actionText = findViewById(R.id.actionText);
        uploadStatusText = findViewById(R.id.uploadStatusText);
        captureButton = findViewById(R.id.captureButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);
        nextActionButton = findViewById(R.id.nextActionButton);  // 新增
        // 在 onCreate 中，现有 findViewById 那些行后面加一行：
        skeletonOverlayView = findViewById(R.id.skeletonOverlayView);

        // 初始化 HTTP 客户端
        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        cameraExecutor = Executors.newSingleThreadExecutor();

        // 获取动作列表
        fetchActionList();

        // 初始化贴纸系统
        initStickers();

        // 初始化贴纸管理器
        stickerManager = new StickerManager(this);

        // 按钮点击事件
        captureButton.setOnClickListener(v -> takePhotoAndUpload());
        switchCameraButton.setOnClickListener(v -> switchCamera());
        nextActionButton.setOnClickListener(v -> manualNextAction());  // 新增：手动切换动作

        // 检查并请求相机权限
        if (checkCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }
    /**
     * 初始化贴纸资源
     */
    private void initStickers() {
        // 创建贴纸（实际项目中应从资源文件加载图片）
        // 这里用颜色圆点模拟，你可以替换成真正的图片资源
        createMockSticker("cat_ears", Color.parseColor("#FFA726"));
        createMockSticker("mustache", Color.parseColor("#795548"));
        createMockSticker("glasses", Color.parseColor("#42A5F5"));
        createMockSticker("crown", Color.parseColor("#FFD700"));
        createMockSticker("heart", Color.parseColor("#EF5350"));
    }

    private void createMockSticker(String name, int color) {
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(color);
        canvas.drawCircle(50, 50, 40, paint);
        stickerBitmaps.put(name, bitmap);
    }

    /**
     * 检查并解锁贴纸
     */
    private void checkAndUnlockStickers() {
        for (Map.Entry<Integer, StickerConfig> entry : STICKER_UNLOCK_CONFIG.entrySet()) {
            int requiredPass = entry.getKey();
            StickerConfig config = entry.getValue();

            // 通关数达到要求，且贴纸还未解锁
            if (passCount >= requiredPass && !isStickerUnlocked(config.name)) {
                Bitmap bmp = stickerBitmaps.get(config.name);
                if (bmp != null) {
                    Sticker sticker = new Sticker(config.name, bmp, requiredPass, config.attachJoint);
                    unlockedStickers.add(sticker);
                    skeletonOverlayView.addSticker(sticker);
                    Toast.makeText(this, "🎁 解锁新贴纸：" + getStickerChineseName(config.name), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private boolean isStickerUnlocked(String name) {
        for (Sticker s : unlockedStickers) {
            if (s.getName().equals(name)) return true;
        }
        return false;
    }

    private String getStickerChineseName(String name) {
        switch (name) {
            case "cat_ears": return "猫耳朵";
            case "mustache": return "小胡子";
            case "glasses": return "酷眼镜";
            case "crown": return "黄金皇冠";
            case "heart": return "爱心特效";
            default: return name;
        }
    }


    /**
     * 获取动作列表
     */
    private void fetchActionList() {
        Request request = new Request.Builder()
                .url(ACTION_LIST_URL)
                .get()
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "获取动作列表失败", e);
                runOnUiThread(() -> {
                    actionList.add("Both Hands Up");
                    actionList.add("Squat");
                    actionList.add("T-Pose");
                    updateActionDisplay();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    Log.d(TAG, "动作列表返回: " + body);
                    try {
                        JSONObject json = new JSONObject(body);
                        if (json.has("poses")) {
                            JSONArray posesArray = json.getJSONArray("poses");
                            actionList.clear();
                            for (int i = 0; i < posesArray.length(); i++) {
                                actionList.add(posesArray.getString(i));
                            }
                            //打乱从后端获取的动作列表
                            Collections.shuffle(actionList);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析动作列表失败", e);
                    }
                }
                runOnUiThread(() -> {
                    if (actionList.isEmpty()) {
                        actionList.add("Both Hands Up");
                        actionList.add("Squat");
                        actionList.add("T-Pose");
                        //打乱默认动作列表
                        Collections.shuffle(actionList);
                    }
                    updateActionDisplay();
                });
            }
        });
    }

    /**
     * 更新动作提示显示
     */
    private void updateActionDisplay() {
        if (!actionList.isEmpty()) {
            actionText.setText(actionList.get(currentActionIndex));
        }
    }

    /**
     * 手动切换到下一个动作（用户主动点击）
     */
    private void manualNextAction() {
        if (actionList.isEmpty()) {
            Toast.makeText(this, "动作列表加载中...", Toast.LENGTH_SHORT).show();
            return;
        }

        // 切换到下一个动作
        currentActionIndex = (currentActionIndex + 1) % actionList.size();
        updateActionDisplay();

        // 显示提示
        String nextAction = actionList.get(currentActionIndex);
        Toast.makeText(this, "⏩ 切换到：" + nextAction, Toast.LENGTH_SHORT).show();

        // 可选：重置分数显示
        // scoreText.setText("--");
    }

    /**
     * 自动切换到下一个动作（分数达标时调用）
     */
    private void nextAction() {
        if (actionList.isEmpty()) return;

        // 闯关成功，增加通关计数
        passCount++;

        // 检查并解锁新贴纸
        List<Sticker> newStickers = stickerManager.checkAndUnlock(passCount);
        for (Sticker sticker : newStickers) {
            skeletonOverlayView.addSticker(sticker);
            Toast.makeText(this, "🎁 解锁贴纸：" + sticker.getName(), Toast.LENGTH_LONG).show();
        }

        // 显示闯关成功提示
        String message = "🎉 闯关成功！连续通关：" + passCount + "关";
        if (stickerManager.getUnlockedStickers().size() > 0) {
            message += "\n✨ 已获得 " + stickerManager.getUnlockedStickers().size() + " 个贴纸";
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();

        currentActionIndex = (currentActionIndex + 1) % actionList.size();
        updateActionDisplay();
    }

    /**
     * 切换摄像头
     */
    private void switchCamera() {
        if (cameraLensFacing == CameraSelector.LENS_FACING_BACK) {
            cameraLensFacing = CameraSelector.LENS_FACING_FRONT;
        } else {
            cameraLensFacing = CameraSelector.LENS_FACING_BACK;
        }
        startCamera();
        Toast.makeText(this, cameraLensFacing == CameraSelector.LENS_FACING_FRONT ? "前置摄像头" : "后置摄像头", Toast.LENGTH_SHORT).show();
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限才能使用拍照功能", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                //1. 配置预览
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                //2. 配置拍照（保留原有功能）
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                //3. 新增配置实时图像分析
                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 忙时自动丢帧
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                    @Override
                    public void analyze(@NonNull ImageProxy image) {
                        long currentTime = System.currentTimeMillis();
                        // 节流：每 250ms (每秒4帧) 传一次实时流，且上一帧已经传输完毕
                        if (currentTime - lastAnalyzeTime > 250 && !isAnalyzing) {
                            isAnalyzing = true;
                            lastAnalyzeTime = currentTime;

                            // 转为 Bitmap 并进行极度压缩，保证实时性
                            Bitmap bitmap = imageProxyToBitmap(image);
                            if (bitmap != null) {
                                // 实时上传评分（轻量压缩）
                                uploadLiveFrameForDrawing(bitmap);
                            }
                        }
                        // 必须关闭，否则 CameraX 会卡死不发新帧
                        image.close();
                    }
                });

                // 选择摄像头
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(cameraLensFacing)
                        .build();

                // 解绑所有
                cameraProvider.unbindAll();
                // 同时绑定三个用例
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "启动相机失败", e);
                runOnUiThread(() -> Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

//    添加实时流上传方法
private void uploadLiveFrameForDrawing(Bitmap bitmap) {
    // 实时流不需要太高分辨率，压到最大 320 像素，大大提升传输和后端 AI 推理速度
    int maxDimension = 320;
    float scale = Math.min((float) maxDimension / bitmap.getWidth(), (float) maxDimension / bitmap.getHeight());
    if (scale < 1) {
        bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
    }

    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, 45, stream); // 质量压到 45%
    byte[] imageBytes = stream.toByteArray();

    MultipartBody.Builder builder = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "live_frame.jpg", RequestBody.create(MediaType.parse("image/jpeg"), imageBytes));
    // 如果后端画关节不需要知道当前是什么动作，甚至不用传 pose_name

    Request request = new Request.Builder()
            .url(BASE_URL + "/pose/draw_joints") // 💡 建议：让后端提供一个专门返回骨骼坐标的轻量接口
            .post(builder.build())
            .build();

    okHttpClient.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(@NonNull Call call, @NonNull IOException e) {
            Log.e(TAG, "实时帧传输失败", e);
            isAnalyzing = false; // 释放锁
        }

        @Override
        public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
            try {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();

                    // 在 UI 线程绘制关节
                    runOnUiThread(() -> {
                        drawSkeletonOnOverlay(responseBody);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "解析实时帧返回失败", e);
            } finally {
                response.close();
                isAnalyzing = false; // 【必须】释放锁，允许下一帧进入
            }
        }
    });
}
    /**
     * 解析后端骨骼点 JSON，更新 SkeletonOverlayView
     * 期望格式：
     * {
     *   "code": 200,
     *   "joints": [{"x": 0.45, "y": 0.32}, {"x": 0.55, "y": 0.32}, ...]
     * }
     */
    private void drawSkeletonOnOverlay(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            int code = json.optInt("code", -1);

            // 没检测到人时清空骨骼
            if (code != 200 || !json.has("joints")) {
                skeletonOverlayView.clearKeypoints();
                return;
            }

            JSONArray jointsArray = json.getJSONArray("joints");
            List<PointF> points = new ArrayList<>();

            for (int i = 0; i < jointsArray.length(); i++) {
                JSONObject joint = jointsArray.getJSONObject(i);
                float x = (float) joint.getDouble("x");
                float y = (float) joint.getDouble("y");
                points.add(new PointF(x, y));
            }

            skeletonOverlayView.updateKeypoints(points);

        } catch (JSONException e) {
            Log.e(TAG, "骨骼点解析失败", e);
            skeletonOverlayView.clearKeypoints();
        }
    }

    private void takePhotoAndUpload() {
        if (imageCapture == null) {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

        showUploadStatus("评分中...");

        imageCapture.takePicture(ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy image) {
                        Bitmap bitmap = imageProxyToBitmap(image);
                        image.close();

                        if (bitmap != null) {
                            uploadImage(bitmap);
                        } else {
                            showUploadStatus("拍照失败");
                            Toast.makeText(MainActivity.this, "拍照失败", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "拍照失败", exception);
                        showUploadStatus("拍照失败");
                        Toast.makeText(MainActivity.this, "拍照失败: " + exception.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /**
     * ImageProxy 转 Bitmap
     */
    private Bitmap imageProxyToBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        if (bitmap == null) {
            return imageProxyToBitmapYuv(image);
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(image.getImageInfo().getRotationDegrees());

        if (cameraLensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1, 1);
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * YUV 格式转 Bitmap
     */
    private Bitmap imageProxyToBitmapYuv(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21,
                image.getWidth(), image.getHeight(), null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 80, out);
        byte[] imageBytes = out.toByteArray();

        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

        Matrix matrix = new Matrix();
        matrix.postRotate(image.getImageInfo().getRotationDegrees());

        if (cameraLensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1, 1);
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private void uploadImage(Bitmap bitmap) {
        // 检查动作列表是否为空
        if (actionList.isEmpty()) {
            Log.e(TAG, "动作列表为空，无法上传");
            runOnUiThread(() -> {
                showUploadStatus("动作列表加载中");
                Toast.makeText(MainActivity.this, "动作列表加载中，请稍后再试", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        // 压缩图片
        int maxDimension = 720;
        float scale = Math.min(
                (float) maxDimension / bitmap.getWidth(),
                (float) maxDimension / bitmap.getHeight()
        );

        if (scale < 1) {
            int newWidth = Math.round(bitmap.getWidth() * scale);
            int newHeight = Math.round(bitmap.getHeight() * scale);
            bitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
        }

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream);
        byte[] imageBytes = stream.toByteArray();

        Log.d(TAG, "上传图片大小: " + (imageBytes.length / 1024) + " KB");

        // 获取当前动作名称（现在 actionList 肯定不为空）
        String currentPose = actionList.get(currentActionIndex);
        Log.d(TAG, "当前动作: " + currentPose);

        // 构建 multipart 请求
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "pose_photo.jpg",
                        RequestBody.create(MediaType.parse("image/jpeg"), imageBytes))
                .addFormDataPart("pose_name", currentPose);

        RequestBody requestBody = builder.build();

        Request request = new Request.Builder()
                .url(UPLOAD_URL)
                .post(requestBody)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "上传失败", e);
                runOnUiThread(() -> {
                    showUploadStatus("上传失败");
                    Toast.makeText(MainActivity.this, "网络错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "后端返回: " + responseBody);

                runOnUiThread(() -> {
                    showUploadStatus(response.isSuccessful() ? "评分完成" : "评分失败");
                    parseScoreResponse(responseBody);
                });
            }
        });
    }

    /**
     * 解析后端返回的评分结果
     */
    /**
     * 解析后端返回的评分结果
     */
    private void parseScoreResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            Log.e(TAG, "返回数据为空");
            return;
        }

        Log.d(TAG, "原始返回: " + responseBody);

        try {
            JSONObject json = new JSONObject(responseBody);
            int code = json.optInt("code", -1);
            String msg = json.optString("msg", "");

            // 处理 400 错误
            if (code == 400) {
                Log.e(TAG, "后端错误: " + msg);
                Toast.makeText(this, "错误: " + msg, Toast.LENGTH_SHORT).show();
                return;
            }

            // 处理 201（未检测到人体）
            if (code == 201) {
                Log.d(TAG, "未检测到人体: " + msg);
                updateScore(0);
                Toast.makeText(this, "未检测到人体，请调整姿势", Toast.LENGTH_SHORT).show();
                return;
            }

            // 处理 200 成功
            if (code == 200 && json.has("data")) {
                JSONObject data = json.getJSONObject("data");

                double totalScore = 0;
                if (data.has("total_score")) {
                    totalScore = data.getDouble("total_score");
                } else if (data.has("score")) {
                    totalScore = data.getDouble("score");
                }

                int score = (int) Math.round(totalScore);
                boolean hasHuman = data.optBoolean("has_human", false);
                String poseName = data.optString("pose_name", "");

                Log.d(TAG, "姿势: " + poseName + ", 有人: " + hasHuman + ", 分数: " + score);
                updateScore(score);

                if (!hasHuman) {
                    Toast.makeText(this, "未检测到人体，请调整姿势", Toast.LENGTH_SHORT).show();
                } else if (score >= 80) {
                    nextAction();
                } else if (score > 0) {
                    Toast.makeText(this, "得分 " + score + " 分，继续努力！", Toast.LENGTH_SHORT).show();
                }
                return;

                // ★ 新增：解析骨骼关键点并绘制
                if (data.has("landmarks")) {
                    JSONArray landmarks = data.getJSONArray("landmarks");
                    List<PointF> points = new ArrayList<>();
                    for (int i = 0; i < landmarks.length(); i++) {
                        JSONObject joint = landmarks.getJSONObject(i);
                        float x = (float) joint.getDouble("x");
                        float y = (float) joint.getDouble("y");
                        points.add(new PointF(x, y));
                    }
                    skeletonOverlayView.updateKeypoints(points);
                }
            }

            // 兼容简单格式
            if (json.has("score")) {
                int score = json.getInt("score");
                updateScore(score);
                if (score == 0) {
                    Toast.makeText(this, "未检测到人体，请调整姿势", Toast.LENGTH_SHORT).show();
                }
                return;
            }

            // 其他未知格式
            Log.e(TAG, "未知返回格式: " + responseBody);
            Toast.makeText(this, "未知返回格式: code=" + code, Toast.LENGTH_SHORT).show();

        } catch (JSONException e) {
            Log.e(TAG, "JSON解析失败: " + responseBody, e);
            Toast.makeText(this, "解析失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateScore(int score) {
        scoreText.setText(String.valueOf(score));
        if (score >= 80) {
            scoreText.setTextColor(getColor(android.R.color.holo_green_light));
        } else if (score >= 60) {
            scoreText.setTextColor(getColor(android.R.color.holo_orange_light));
        } else {
            scoreText.setTextColor(getColor(android.R.color.holo_red_light));
        }
    }

    private void showUploadStatus(String status) {
        uploadStatusText.setText(status);
        uploadStatusText.setVisibility(View.VISIBLE);
        uploadStatusText.postDelayed(() -> {
            if (uploadStatusText.getVisibility() == View.VISIBLE) {
                uploadStatusText.setVisibility(View.GONE);
            }
        }, 2000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}