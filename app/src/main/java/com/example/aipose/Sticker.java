package com.example.aipose;

import android.graphics.Bitmap;

public class Sticker {
    public enum StickerType {
        FACE_SMALL,   // 面部小贴纸（猫耳、眼镜、胡子等）
        BODY_LARGE    // 身体大贴纸（半身、全身装饰）
    }

    private String name;
    private Bitmap bitmap;
    private int unlockLevel;
    private String attachJoint;      // 附着关节
    private StickerType type;
    private float scale;              // 缩放比例（大贴纸0.5，小贴纸0.12）
    private float offsetX;            // X轴偏移
    private float offsetY;            // Y轴偏移

    public Sticker(String name, Bitmap bitmap, int unlockLevel, String attachJoint, StickerType type) {
        this.name = name;
        this.bitmap = bitmap;
        this.unlockLevel = unlockLevel;
        this.attachJoint = attachJoint;
        this.type = type;

        // 根据类型设置默认缩放
        if (type == StickerType.FACE_SMALL) {
            this.scale = 0.12f;
            this.offsetX = 0;
            this.offsetY = 0;
        } else {
            this.scale = 0.5f;
            this.offsetX = 0;
            this.offsetY = 0;
        }
    }

    // Getters
    public String getName() { return name; }
    public Bitmap getBitmap() { return bitmap; }
    public int getUnlockLevel() { return unlockLevel; }
    public String getAttachJoint() { return attachJoint; }
    public StickerType getType() { return type; }
    public float getScale() { return scale; }
    public float getOffsetX() { return offsetX; }
    public float getOffsetY() { return offsetY; }

    // Setters for custom positioning
    public void setOffset(float offsetX, float offsetY) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    public void setScale(float scale) {
        this.scale = scale;
    }
}