package com.remote.client.ui;

import com.remote.client.network.EventSender;
import com.remote.common.model.FramePacket;
import com.remote.common.model.InputEventPacket;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public class ScreenPanel extends JPanel implements MouseListener, MouseMotionListener, MouseWheelListener, KeyListener {
    private BufferedImage currentFrame;
    private EventSender eventSender;

    public ScreenPanel() {
        setDoubleBuffered(true);
        setBackground(Color.BLACK);
        setFocusable(true); // Kích hoạt panel nhận sự kiện bàn phím

        // Lắng nghe đầy đủ sự kiện chuột & phím
        addMouseListener(this);
        addMouseMotionListener(this);
        addMouseWheelListener(this);
        addKeyListener(this);
    }

    /**
     * Gắn EventSender sau khi kết nối Socket đã thành công.
     */
    public void setEventSender(EventSender eventSender) {
        this.eventSender = eventSender;
    }

    /**
     * Hàm nhận FramePacket giữ nguyên logic hiển thị ảnh của nhóm.
     */
    public void onFrameReceived(FramePacket framePacket) {
        if (framePacket == null || framePacket.getData() == null) return;

        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(framePacket.getData());
            BufferedImage newImage = ImageIO.read(bais);

            if (newImage != null) {
                this.currentFrame = newImage;
                repaint();
            }
        } catch (IOException e) {
            System.err.println("[ScreenPanel] Lỗi giải mã khung hình: " + e.getMessage());
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (currentFrame != null) {
            g.drawImage(currentFrame, 0, 0, getWidth(), getHeight(), this);
        } else {
            g.setColor(Color.WHITE);
            g.drawString("Đang chờ luồng hình ảnh từ máy Host...", getWidth() / 2 - 100, getHeight() / 2);
        }
    }

    // -------------------------------------------------------------------------
    // Bắt sự kiện Chuột (Mouse Events)
    // -------------------------------------------------------------------------

    @Override
    public void mouseMoved(MouseEvent e) {
        if (eventSender == null || getWidth() <= 0 || getHeight() <= 0) return;
        float xRatio = (float) e.getX() / getWidth();
        float yRatio = (float) e.getY() / getHeight();

        InputEventPacket packet = InputEventPacket.createMouseMove(xRatio, yRatio);
        eventSender.send(packet);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        mouseMoved(e);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        requestFocusInWindow(); // Click chuột vào là bắt focus để gõ phím ngay
        sendMouseButtonEvent(InputEventPacket.Type.MOUSE_PRESS, e);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        sendMouseButtonEvent(InputEventPacket.Type.MOUSE_RELEASE, e);
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (eventSender == null) return;
        InputEventPacket packet = InputEventPacket.createMouseWheel(e.getWheelRotation());
        eventSender.send(packet);
    }

    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    // -------------------------------------------------------------------------
    // Bắt sự kiện Bàn phím (Key Events)
    // -------------------------------------------------------------------------

    @Override
    public void keyPressed(KeyEvent e) {
        if (eventSender == null) return;
        InputEventPacket packet = InputEventPacket.createKey(InputEventPacket.Type.KEY_PRESS, e.getKeyCode());
        eventSender.send(packet);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (eventSender == null) return;
        InputEventPacket packet = InputEventPacket.createKey(InputEventPacket.Type.KEY_RELEASE, e.getKeyCode());
        eventSender.send(packet);
    }

    @Override public void keyTyped(KeyEvent e) {}

    // -------------------------------------------------------------------------
    // Helper: Ánh xạ nút chuột và tính tỷ lệ tọa độ
    // -------------------------------------------------------------------------

    private void sendMouseButtonEvent(InputEventPacket.Type type, MouseEvent e) {
        if (eventSender == null || getWidth() <= 0 || getHeight() <= 0) return;

        float xRatio = (float) e.getX() / getWidth();
        float yRatio = (float) e.getY() / getHeight();

        int button = switch (e.getButton()) {
            case MouseEvent.BUTTON1 -> InputEventPacket.BUTTON_LEFT;
            case MouseEvent.BUTTON2 -> InputEventPacket.BUTTON_MIDDLE;
            case MouseEvent.BUTTON3 -> InputEventPacket.BUTTON_RIGHT;
            default -> InputEventPacket.BUTTON_LEFT;
        };

        InputEventPacket packet = InputEventPacket.createMouseButton(type, button, xRatio, yRatio);
        eventSender.send(packet);
    }
}