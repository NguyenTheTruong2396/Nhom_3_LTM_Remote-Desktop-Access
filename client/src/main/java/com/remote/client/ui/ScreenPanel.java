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

    // Vùng vẽ ảnh thực tế (đảm bảo đúng tỷ lệ chuẩn của màn hình Host, không bị kéo dãn bẹt hình)
    private int drawX = 0;
    private int drawY = 0;
    private int drawW = 1;
    private int drawH = 1;

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (currentFrame != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int imgW = currentFrame.getWidth();
            int imgH = currentFrame.getHeight();
            int panelW = getWidth();
            int panelH = getHeight();

            if (imgW > 0 && imgH > 0 && panelW > 0 && panelH > 0) {
                // Tính toán tỷ lệ co giãn giữ nguyên Aspect Ratio (không bị kéo dẹt sang 2 bên)
                double scale = Math.min((double) panelW / imgW, (double) panelH / imgH);
                drawW = Math.max(1, (int) (imgW * scale));
                drawH = Math.max(1, (int) (imgH * scale));
                drawX = (panelW - drawW) / 2;
                drawY = (panelH - drawH) / 2;

                g2.drawImage(currentFrame, drawX, drawY, drawW, drawH, this);
            }
            g2.dispose();
        } else {
            g.setColor(new Color(180, 180, 180));
            g.setFont(new Font("Segoe UI", Font.BOLD, 15));
            String msg = "Đang chờ luồng hình ảnh từ máy Host...";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
        }
    }

    // -------------------------------------------------------------------------
    // Bắt sự kiện Chuột (Mouse Events) chuẩn xác theo vùng vẽ ảnh
    // -------------------------------------------------------------------------

    @Override
    public void mouseMoved(MouseEvent e) {
        if (eventSender == null || drawW <= 0 || drawH <= 0) return;
        float xRatio = clamp((float) (e.getX() - drawX) / drawW);
        float yRatio = clamp((float) (e.getY() - drawY) / drawH);

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
    // Helper: Ánh xạ nút chuột và tính tỷ lệ tọa độ theo đúng khung hình
    // -------------------------------------------------------------------------

    private void sendMouseButtonEvent(InputEventPacket.Type type, MouseEvent e) {
        if (eventSender == null || drawW <= 0 || drawH <= 0) return;

        float xRatio = clamp((float) (e.getX() - drawX) / drawW);
        float yRatio = clamp((float) (e.getY() - drawY) / drawH);

        int button = switch (e.getButton()) {
            case MouseEvent.BUTTON1 -> InputEventPacket.BUTTON_LEFT;
            case MouseEvent.BUTTON2 -> InputEventPacket.BUTTON_MIDDLE;
            case MouseEvent.BUTTON3 -> InputEventPacket.BUTTON_RIGHT;
            default -> InputEventPacket.BUTTON_LEFT;
        };

        InputEventPacket packet = InputEventPacket.createMouseButton(type, button, xRatio, yRatio);
        eventSender.send(packet);
    }

    private static float clamp(float val) {
        if (val < 0.0f) return 0.0f;
        if (val > 1.0f) return 1.0f;
        return val;
    }
}