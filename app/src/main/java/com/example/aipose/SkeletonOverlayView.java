package com.example.aipose;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SkeletonOverlayView extends View {

    // ── 骨骼连线拓扑表（COCO 17关键点格式）──
    // 每对数字代表：从关键点A 连线到 关键点B
    // MediaPipe 33点连线（与后端索引完全对应）
    private static final int[][] CONNECTIONS = {
            // 脸部
            {0,1},{1,2},{2,3},{3,7},{0,4},{4,5},{5,6},{6,8},
            // 躯干
            {9,10},{11,12},{11,13},{13,15},{12,14},{14,16},
            {11,23},{12,24},{23,24},
            // 左腿
            {23,25},{25,27},{27,29},{29,31},{27,31},
            // 右腿
            {24,26},{26,28},{28,30},{30,32},{28,32},
            // 手
            {15,17},{15,19},{15,21},{17,19},
            {16,18},{16,20},{16,22},{18,20},
    };

    // 关节名称映射（用于贴纸定位）
    private static final Map<String, Integer> JOINT_MAP = new HashMap<>();
    static {
        JOINT_MAP.put("nose", 0);
        JOINT_MAP.put("left_eye", 1);
        JOINT_MAP.put("right_eye", 2);
        JOINT_MAP.put("left_ear", 3);
        JOINT_MAP.put("right_ear", 4);
        JOINT_MAP.put("left_shoulder", 11);
        JOINT_MAP.put("right_shoulder", 12);
        JOINT_MAP.put("left_elbow", 13);
        JOINT_MAP.put("right_elbow", 14);
        JOINT_MAP.put("left_wrist", 15);
        JOINT_MAP.put("right_wrist", 16);
        JOINT_MAP.put("left_hip", 23);
        JOINT_MAP.put("right_hip", 24);
        JOINT_MAP.put("left_knee", 25);
        JOINT_MAP.put("right_knee", 26);
        JOINT_MAP.put("left_ankle", 27);
        JOINT_MAP.put("right_ankle", 28);
        JOINT_MAP.put("head", 0);
    }
    private final Paint linePaint;
    private final Paint dotPaint;
    private List<PointF> keypoints; // 归一化坐标 [0, 1]

    // 贴纸相关
    private List<Sticker> activeStickers = new ArrayList<>();
    private boolean showStickers = false;

    public SkeletonOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // 骨骼连线画笔
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#00FF88")); // 荧光绿，在深色背景高对比
        linePaint.setStrokeWidth(4f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        // 关键点圆点画笔
        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.parseColor("#FF4444")); // 红色关键点
        dotPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * 外部调用此方法更新关键点并触发重绘
     * @param points 归一化坐标列表（x, y 均在 [0,1]），索引对应 COCO 关键点编号
     */
    public void updateKeypoints(List<PointF> points) {
        this.keypoints = points;
        invalidate(); // 触发 onDraw 重绘
    }

    /**
     * 清空骨骼（检测不到人时调用）
     */
    public void clearKeypoints() {
        this.keypoints = null;
        invalidate();
    }

    /**
     * 添加贴纸
     */
    public void addSticker(Sticker sticker) {
        activeStickers.add(sticker);
        showStickers = true;
        invalidate();
    }
    /**
     * 清空所有贴纸
     */
    public void clearStickers() {
        activeStickers.clear();
        showStickers = false;
        invalidate();
    }

    /**
     * 获取已解锁的贴纸数量
     */
    public int getStickerCount() {
        return activeStickers.size();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float viewW = getWidth();
        float viewH = getHeight();

        // 绘制骨骼
        if (keypoints != null && !keypoints.isEmpty()) {
            // 画连线
            for (int[] conn : CONNECTIONS) {
                int idxA = conn[0];
                int idxB = conn[1];

                if (idxA >= keypoints.size() || idxB >= keypoints.size()) continue;

                PointF ptA = keypoints.get(idxA);
                PointF ptB = keypoints.get(idxB);

                if ((ptA.x <= 0 && ptA.y <= 0) || (ptB.x <= 0 && ptB.y <= 0)) continue;

                float x1 = ptA.x * viewW;
                float y1 = ptA.y * viewH;
                float x2 = ptB.x * viewW;
                float y2 = ptB.y * viewH;

                canvas.drawLine(x1, y1, x2, y2, linePaint);
            }

            // 画关键点
            for (PointF pt : keypoints) {
                if (pt.x <= 0 && pt.y <= 0) continue;
                float cx = pt.x * viewW;
                float cy = pt.y * viewH;
                canvas.drawCircle(cx, cy, 6f, dotPaint);
            }
        }

        // 绘制贴纸
        if (showStickers && keypoints != null && !activeStickers.isEmpty()) {
            for (Sticker sticker : activeStickers) {
                drawSticker(canvas, sticker, viewW, viewH);
            }
        }
    }

    private void drawSticker(Canvas canvas, Sticker sticker, float viewW, float viewH) {
        Integer jointIndex = JOINT_MAP.get(sticker.getAttachJoint());
        if (jointIndex == null || jointIndex >= keypoints.size()) return;

        PointF jointPos = keypoints.get(jointIndex);
        if (jointPos == null || (jointPos.x <= 0 && jointPos.y <= 0)) return;

        float x = jointPos.x * viewW;
        float y = jointPos.y * viewH;

        // 应用偏移
        x += sticker.getOffsetX() * viewW;
        y += sticker.getOffsetY() * viewH;

        Bitmap stickerBmp = sticker.getBitmap();
        if (stickerBmp == null) return;

        // 根据贴纸类型计算大小
        int stickerSize;
        if (sticker.getType() == Sticker.StickerType.FACE_SMALL) {
            // 面部小贴纸：基于屏幕宽度
            stickerSize = (int)(viewW * sticker.getScale());
        } else {
            // 身体大贴纸：基于身体宽度（左右肩距离）
            float bodyWidth = getBodyWidth();
            if (bodyWidth > 0) {
                stickerSize = (int)(bodyWidth * sticker.getScale());
            } else {
                stickerSize = (int)(viewW * sticker.getScale());
            }
        }

        Bitmap scaled = Bitmap.createScaledBitmap(stickerBmp, stickerSize, stickerSize, true);
        canvas.drawBitmap(scaled, x - stickerSize/2, y - stickerSize/2, null);
    }

    private float getBodyWidth() {
        if (keypoints == null) return 0;
        // 左右肩关节索引：11=左肩, 12=右肩
        if (keypoints.size() <= 12) return 0;
        PointF leftShoulder = keypoints.get(11);
        PointF rightShoulder = keypoints.get(12);
        if (leftShoulder == null || rightShoulder == null) return 0;
        float width = Math.abs(leftShoulder.x - rightShoulder.x);
        return width * getWidth();  // 转换为像素宽度
    }
}