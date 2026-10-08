package com.remote.common.model;

import java.io.Serializable;

public class InputEventPacket implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        MOUSE_MOVE,
        MOUSE_PRESS,
        MOUSE_RELEASE,
        MOUSE_WHEEL,
        KEY_PRESS,
        KEY_RELEASE
    }

    private Type type;
    // Dùng tỉ lệ x, y từ 0.0f đến 1.0f để thích ứng mọi kích thước màn hình
    private float xRatio;
    private float yRatio;
    private int button;        // Chuột trái/phải/giữa
    private int wheelRotation; // Lượng lăn chuột
    private int keyCode;       // Mã phím bấm

    // Constructors & Getters/Setters
    public InputEventPacket() {}

    // Factory methods tiện lợi
    public static InputEventPacket createMouseMove(float x, float y) {
        InputEventPacket p = new InputEventPacket();
        p.type = Type.MOUSE_MOVE;
        p.xRatio = x;
        p.yRatio = y;
        return p;
    }

    public static InputEventPacket createMouseButton(Type type, int button, float x, float y) {
        InputEventPacket p = new InputEventPacket();
        p.type = type;
        p.button = button;
        p.xRatio = x;
        p.yRatio = y;
        return p;
    }

    public static InputEventPacket createMouseWheel(int wheelRotation) {
        InputEventPacket p = new InputEventPacket();
        p.type = Type.MOUSE_WHEEL;
        p.wheelRotation = wheelRotation;
        return p;
    }

    public static InputEventPacket createKey(Type type, int keyCode) {
        InputEventPacket p = new InputEventPacket();
        p.type = type;
        p.keyCode = keyCode;
        return p;
    }

    public Type getType() { return type; }
    public float getXRatio() { return xRatio; }
    public float getYRatio() { return yRatio; }
    public int getButton() { return button; }
    public int getWheelRotation() { return wheelRotation; }
    public int getKeyCode() { return keyCode; }
}