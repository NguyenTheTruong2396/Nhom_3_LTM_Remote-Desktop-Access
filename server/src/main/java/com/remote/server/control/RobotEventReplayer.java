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
        if (robot == null || event == null) return;

        switch (event.getType()) {
            case MOUSE_MOVE -> {
                int actualX = (int) (event.getXRatio() * screenSize.width);
                int actualY = (int) (event.getYRatio() * screenSize.height);
                robot.mouseMove(actualX, actualY);
            }
            case MOUSE_PRESS -> {
                int actualX = (int) (event.getXRatio() * screenSize.width);
                int actualY = (int) (event.getYRatio() * screenSize.height);
                robot.mouseMove(actualX, actualY);
                robot.mousePress(event.getButton());
            }
            case MOUSE_RELEASE -> {
                robot.mouseRelease(event.getButton());
            }
            case MOUSE_WHEEL -> {
                robot.mouseWheel(event.getWheelRotation());
            }
            case KEY_PRESS -> {
                try {
                    robot.keyPress(event.getKeyCode());
                } catch (IllegalArgumentException ignored) {
                    // Tránh crash nếu gặp keycode đặc thù không được hỗ trợ
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
}