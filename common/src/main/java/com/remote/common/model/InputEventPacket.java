package com.remote.common.model;

import java.io.Serializable;
import java.nio.ByteBuffer;

/**
 * Lớp đại diện cho sự kiện đầu vào bàn phím/chuột.
 * Hỗ trợ chuyển đổi Compact Binary (17 bytes) để tối ưu độ trễ mạng và loại bỏ
 * Object serialization overhead.
 */
public class InputEventPacket implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int BINARY_SIZE = 17;

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

    /**
     * Chuyển đổi gói tin sang mảng nhị phân cố định 17 bytes:
     * Byte 0: Type ordinal (1B)
     * Byte 1-4: xRatio (4B float)
     * Byte 5-8: yRatio (4B float)
     * Byte 9-12: button hoặc keyCode (4B int)
     * Byte 13-16: wheelRotation (4B int)
     */
    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(BINARY_SIZE);
        buf.put((byte) (type != null ? type.ordinal() : 0));
        buf.putFloat(xRatio);
        buf.putFloat(yRatio);
        buf.putInt(type == Type.KEY_PRESS || type == Type.KEY_RELEASE ? keyCode : button);
        buf.putInt(wheelRotation);
        return buf.array();
    }

    /**
     * Giải mã mảng byte nhị phân 17 bytes thành đối tượng InputEventPacket.
     *
     * @param bytes Mảng byte chứa dữ liệu nhị phân (tối thiểu 17 bytes)
     * @return InputEventPacket được tái tạo
     */
    public static InputEventPacket fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < BINARY_SIZE) {
            throw new IllegalArgumentException("Dữ liệu nhị phân không đủ " + BINARY_SIZE + " bytes: " + (bytes != null ? bytes.length : 0));
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes);
        byte typeOrdinal = buf.get();
        float xRatio = buf.getFloat();
        float yRatio = buf.getFloat();
        int buttonOrKeyCode = buf.getInt();
        int wheelRotation = buf.getInt();

        Type[] types = Type.values();
        Type type = (typeOrdinal >= 0 && typeOrdinal < types.length) ? types[typeOrdinal] : Type.MOUSE_MOVE;

        InputEventPacket packet = new InputEventPacket();
        packet.type = type;
        packet.xRatio = xRatio;
        packet.yRatio = yRatio;
        packet.wheelRotation = wheelRotation;

        if (type == Type.KEY_PRESS || type == Type.KEY_RELEASE) {
            packet.keyCode = buttonOrKeyCode;
        } else {
            packet.button = buttonOrKeyCode;
        }

        return packet;
    }

    public Type getType() { return type; }
    public float getXRatio() { return xRatio; }
    public float getYRatio() { return yRatio; }
    public int getButton() { return button; }
    public int getWheelRotation() { return wheelRotation; }
    public int getKeyCode() { return keyCode; }
}