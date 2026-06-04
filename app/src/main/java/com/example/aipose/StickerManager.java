package com.example.aipose;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StickerManager {
    private static final String TAG = "StickerManager";

    private Context context;
    private Map<String, Bitmap> stickerBitmaps = new HashMap<>();
    private List<Sticker> allStickers = new ArrayList<>();
    private List<Sticker> unlockedStickers = new ArrayList<>();
    private int currentPassCount = 0;

    // 贴纸配置列表
    private static class StickerConfig {
        String name;
        String fileName;
        int unlockLevel;
        String attachJoint;
        Sticker.StickerType type;
        float offsetX;
        float offsetY;
        float scale;

        StickerConfig(String name, String fileName, int unlockLevel, String attachJoint,
                      Sticker.StickerType type, float offsetX, float offsetY, float scale) {
            this.name = name;
            this.fileName = fileName;
            this.unlockLevel = unlockLevel;
            this.attachJoint = attachJoint;
            this.type = type;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.scale = scale;
        }
    }

    private StickerConfig[] stickerConfigs = {
            // 面部小贴纸
            new StickerConfig("猫耳朵", "rabbet.png", 1, "head", Sticker.StickerType.FACE_SMALL, 0, -0.08f, 0.12f),
            new StickerConfig("墨镜", "sunglasses.png", 2, "nose", Sticker.StickerType.FACE_SMALL, 0, 0, 0.15f),
            new StickerConfig("胡子", "bow.png", 3, "nose", Sticker.StickerType.FACE_SMALL, 0, 0.05f, 0.1f),
            new StickerConfig("微笑", "smile.png", 4, "nose", Sticker.StickerType.FACE_SMALL, 0, 0.08f, 0.1f),
            new StickerConfig("星星", "star.png", 5, "head", Sticker.StickerType.FACE_SMALL, 0.1f, -0.1f, 0.08f),
            new StickerConfig("哇哦", "wow.png", 6, "nose", Sticker.StickerType.FACE_SMALL, 0, 0, 0.12f),
            new StickerConfig("粉红心", "pinkheart.png", 7, "nose", Sticker.StickerType.FACE_SMALL, 0, 0.05f, 0.1f),
            new StickerConfig("粉红EYu", "pinkeyu.png", 8, "head", Sticker.StickerType.FACE_SMALL, 0.1f, -0.12f, 0.1f),

            // 身体大贴纸
            new StickerConfig("半身黑1", "halfbodyblack1.png", 9, "left_shoulder", Sticker.StickerType.BODY_LARGE, -0.3f, 0.2f, 0.8f),
            new StickerConfig("半身黑2", "halfbodyblack2.png", 10, "left_shoulder", Sticker.StickerType.BODY_LARGE, -0.3f, 0.2f, 0.8f),
            new StickerConfig("半身黑3", "halfbodyblack3.png", 11, "left_shoulder", Sticker.StickerType.BODY_LARGE, -0.3f, 0.2f, 0.8f),
            new StickerConfig("半身黑4", "halfbodyblack4.png", 12, "left_shoulder", Sticker.StickerType.BODY_LARGE, -0.3f, 0.2f, 0.8f),
            new StickerConfig("半身黑5", "halfbodyblack5.png", 13, "left_shoulder", Sticker.StickerType.BODY_LARGE, -0.3f, 0.2f, 0.8f),
            new StickerConfig("小黄狗", "yellowdog.png", 14, "left_shoulder", Sticker.StickerType.BODY_LARGE, -0.25f, 0.15f, 0.6f),
    };

    public StickerManager(Context context) {
        this.context = context;
        loadAllStickers();
    }

    private void loadAllStickers() {
        for (StickerConfig config : stickerConfigs) {
            try {
                // 从 assets 目录加载图片
                String packageName = context.getPackageName();
                int resId = context.getResources().getIdentifier(
                        config.fileName.replace(".png", ""),
                        "drawable",
                        packageName
                );

                Bitmap bitmap;
                if (resId != 0) {
                    bitmap = BitmapFactory.decodeResource(context.getResources(), resId);
                } else {
                    // 如果资源不存在，创建默认色块
                    bitmap = createDefaultBitmap(config.name);
                    Log.w(TAG, "贴纸资源不存在，使用默认: " + config.fileName);
                }

                Sticker sticker = new Sticker(config.name, bitmap, config.unlockLevel,
                        config.attachJoint, config.type);
                sticker.setOffset(config.offsetX, config.offsetY);
                sticker.setScale(config.scale);
                allStickers.add(sticker);
                stickerBitmaps.put(config.name, bitmap);

            } catch (Exception e) {
                Log.e(TAG, "加载贴纸失败: " + config.fileName, e);
            }
        }
    }

    private Bitmap createDefaultBitmap(String name) {
        Bitmap bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.parseColor("#FFA726"));
        canvas.drawCircle(100, 100, 80, paint);
        return bitmap;
    }

    /**
     * 检查并解锁新贴纸
     */
    public List<Sticker> checkAndUnlock(int newPassCount) {
        List<Sticker> newlyUnlocked = new ArrayList<>();
        currentPassCount = newPassCount;

        for (Sticker sticker : allStickers) {
            if (!isStickerUnlocked(sticker.getName()) && newPassCount >= sticker.getUnlockLevel()) {
                unlockedStickers.add(sticker);
                newlyUnlocked.add(sticker);
            }
        }

        return newlyUnlocked;
    }

    private boolean isStickerUnlocked(String name) {
        for (Sticker s : unlockedStickers) {
            if (s.getName().equals(name)) return true;
        }
        return false;
    }

    public List<Sticker> getUnlockedStickers() {
        return unlockedStickers;
    }

    public void clearUnlockedStickers() {
        unlockedStickers.clear();
    }

    public int getPassCount() {
        return currentPassCount;
    }
}