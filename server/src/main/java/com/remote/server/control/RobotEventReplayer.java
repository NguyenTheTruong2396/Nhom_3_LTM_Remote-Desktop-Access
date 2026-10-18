package com.remote.server.control;

import com.remote.common.model.InputEventPacket;

import java.awt.*;

public class RobotEventReplayer {
    private Robot robot;
    private final Dimension screenSize;

    public RobotEventReplayer() {
        this.screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        try {
            this.robot = new Robot();
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
                try {
                    robot.keyPress(event.getKeyCode());
                } catch (IllegalArgumentException ignored) {
                    // Tránh crash nếu gặp keycode đặc thù của hệ điều hành không được Robot hỗ trợ
                }
            }
            case KEY_RELEASE -> {
                try {
                    robot.keyRelease(event.getKeyCode());
                } catch (IllegalArgumentException ignored) {
                }
            }
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