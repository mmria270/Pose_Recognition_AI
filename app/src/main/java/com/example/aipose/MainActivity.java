package com.example.aipose;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
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
import java.util.List;
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

        // 初始化 HTTP 客户端
        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        cameraExecutor = Executors.newSingleThreadExecutor();

        // 获取动作列表
        fetchActionList();

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
        currentActionIndex = (currentActionIndex + 1) % actionList.size();
        updateActionDisplay();
        Toast.makeText(this, "🎉 闯关成功！下一个动作：" + actionList.get(currentActionIndex), Toast.LENGTH_LONG).show();
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

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(cameraLensFacing)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "启动相机失败", e);
                runOnUiThread(() -> Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show());
            }
        }, ContextCompat.getMainExecutor(this));
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