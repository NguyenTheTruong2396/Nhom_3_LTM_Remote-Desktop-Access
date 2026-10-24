package com.remote.server.control;

import com.remote.common.model.InputEventPacket;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RobotEventReplayer {
    private Robot robot;
    private final Dimension screenSize;
    private final Set<Integer> pressedKeys = ConcurrentHashMap.newKeySet();

    public RobotEventReplayer() {
        this.screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        try {
            this.robot = new Robot();
            // Đảm bảo không bị kẹt các phím điều khiển (như Windows, Ctrl, Alt) từ phiên trước
            releaseAllKeys();
        } catch (AWTException e) {
            e.printStackTrace();
            this.robot = null;
        }
    }

    public void processEvent(InputEventPacket event) {
        if (robot == null || event == null || event.getType() == null) return;

        switch (event.getType()) {
            case MOUSE_MOVE -> {
                int actualX = clampX(event.getXRatio());
                int actualY = clampY(event.getYRatio());
                robot.mouseMove(actualX, actualY);
            }
            case MOUSE_PRESS -> {
                int actualX = clampX(event.getXRatio());
                int actualY = clampY(event.getYRatio());
                robot.mouseMove(actualX, actualY);
                try {
                    robot.mousePress(getMouseMask(event.getButton()));
                } catch (IllegalArgumentException e) {
                    System.err.println("[RobotEventReplayer] Lỗi mousePress với button " + event.getButton() + ": " + e.getMessage());
                }
            }
            case MOUSE_RELEASE -> {
                try {
                    robot.mouseRelease(getMouseMask(event.getButton()));
                } catch (IllegalArgumentException e) {
                    System.err.println("[RobotEventReplayer] Lỗi mouseRelease với button " + event.getButton() + ": " + e.getMessage());
                }
            }
            case MOUSE_WHEEL -> {
                try {
                    robot.mouseWheel(event.getWheelRotation());
                } catch (IllegalArgumentException ignored) {}
            }
            case KEY_PRESS -> {
                int keyCode = event.getKeyCode();
                if (keyCode <= 0) return;

                // Xử lý đặc thù cho phím Windows (VK_WINDOWS):
                // Phím Windows trên hệ điều hành Windows rất dễ gây kẹt phím nếu giữ trạng thái DOWN,
                // khiến các phím gõ tiếp theo (như 's') bị hiểu thành Win+S (mở Start Menu / Search).
                // Do đó, khi nhận lệnh phím Windows, thực hiện TAP (nhấn và nhả ngay) để bật/tắt Start Menu an toàn.
                if (keyCode == KeyEvent.VK_WINDOWS) {
                    try {
                        robot.keyPress(KeyEvent.VK_WINDOWS);
                        robot.keyRelease(KeyEvent.VK_WINDOWS);
                    } catch (IllegalArgumentException ignored) {}
                    return;
                }

                try {
                    robot.keyPress(keyCode);
                    pressedKeys.add(keyCode);
                } catch (IllegalArgumentException ignored) {
                    // Tránh crash nếu gặp keycode đặc thù của hệ điều hành không được Robot hỗ trợ
                }
            }
            case KEY_RELEASE -> {
                int keyCode = event.getKeyCode();
                if (keyCode <= 0) return;

                if (keyCode == KeyEvent.VK_WINDOWS) {
                    // Phím Windows đã được nhả an toàn ngay từ lúc KEY_PRESS nên không cần nhả lại
                    return;
                }

                try {
                    robot.keyRelease(keyCode);
                    pressedKeys.remove(keyCode);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    /**
     * Giải phóng toàn bộ các phím đang được giữ và các phím điều khiển (modifiers)
     * để chống hoàn toàn lỗi kẹt phím (như kẹt phím Windows, Ctrl, Alt, Shift).
     */
    public void releaseAllKeys() {
        if (robot == null) return;

        for (Integer code : pressedKeys) {
            try {
                robot.keyRelease(code);
            } catch (Exception ignored) {}
        }
        pressedKeys.clear();

        int[] commonModifiers = {
            KeyEvent.VK_WINDOWS,
            KeyEvent.VK_CONTROL,
            KeyEvent.VK_ALT,
            KeyEvent.VK_SHIFT,
            KeyEvent.VK_META
        };
        for (int mod : commonModifiers) {
            try {
                robot.keyRelease(mod);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Chuyển đổi mã nút chuột (1: Trái, 2: Giữa, 3: Phải) sang InputEvent button mask.
     */
    private int getMouseMask(int button) {
        return switch (button) {
            case 1 -> java.awt.event.InputEvent.BUTTON1_DOWN_MASK; // Chuột trái
            case 2 -> java.awt.event.InputEvent.BUTTON2_DOWN_MASK; // Chuột giữa
            case 3 -> java.awt.event.InputEvent.BUTTON3_DOWN_MASK; // Chuột phải
            default -> {
                try {
                    yield java.awt.event.InputEvent.getMaskForButton(button);
                } catch (Exception e) {
                    yield java.awt.event.InputEvent.BUTTON1_DOWN_MASK;
                }
            }
        };
    }

    private int clampX(float ratio) {
        int x = (int) (ratio * screenSize.width);
        return Math.max(0, Math.min(x, Math.max(0, screenSize.width - 1)));
    }

    private int clampY(float ratio) {
        int y = (int) (ratio * screenSize.height);
        return Math.max(0, Math.min(y, Math.max(0, screenSize.height - 1)));
    }
}