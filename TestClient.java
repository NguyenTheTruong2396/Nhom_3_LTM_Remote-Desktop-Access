import com.remote.client.network.EventSender;
import com.remote.common.model.FramePacket;
import com.remote.common.model.InputEventPacket;
import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketParser;
import com.remote.common.network.PacketType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.Scanner;

/**
 * Client thử nghiệm tích hợp:
 * 1. Gửi AUTH_REQ (Type = 0x01) với SessionID + Passcode
 * 2. Đọc AUTH_RESP (Type = 0x02)
 * 3. Chạy luồng đọc FRAME_DATA (Type = 0x03) liên tục (đo FPS + Latency)
 * 4. Gửi thử nghiệm sự kiện chuột INPUT_EVENT (Type = 0x04) qua EventSender để kiểm tra Robot replay trên Host.
 */
public class TestClient {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("=========================================");
        System.out.println("     Remote Desktop - Test Client        ");
        System.out.println("=========================================");
        System.out.print("Nhập Session ID: ");
        String sessionId = scanner.nextLine().trim();

        System.out.print("Nhập Passcode: ");
        String passcode = scanner.nextLine().trim();

        try {
            Socket socket = new Socket("127.0.0.1", 8888);
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

            System.out.println("[TestClient] Đã kết nối TCP tới Server (127.0.0.1:8888)!");

            // 1. Gửi AUTH_REQ
            PacketBuilder.writeAuthRequest(dos, sessionId, passcode);
            System.out.println("[TestClient] Đã gửi AUTH_REQ (" + sessionId + "|" + passcode + ")");

            // 2. Nhận AUTH_RESP
            PacketParser.Packet authResp = PacketParser.read(dis);
            if (authResp.type() != PacketType.AUTH_RESP || authResp.firstByteAsInt() != 1) {
                System.err.println("[TestClient] Xác thực THẤT BẠI từ Server! Đóng kết nối.");
                socket.close();
                return;
            }
            System.out.println("[TestClient] Xác thực THÀNH CÔNG! Bắt đầu kiểm tra luồng video và input...");

            // 3. Khởi tạo EventSender để gửi thử lệnh chuột
            EventSender eventSender = new EventSender(dos);

            // Gửi thử 1 chuỗi sự kiện chuột di chuyển và click thử
            new Thread(() -> {
                try {
                    Thread.sleep(1500); // Chờ 1.5s sau khi luồng video bắt đầu
                    System.out.println("[TestClient -> InputTest] Bắt đầu gửi sự kiện di chuyển chuột (Center screen)...");
                    eventSender.send(InputEventPacket.createMouseMove(0.5f, 0.5f));

                    Thread.sleep(500);
                    System.out.println("[TestClient -> InputTest] Gửi sự kiện di chuyển chuột (Top-Left)...");
                    eventSender.send(InputEventPacket.createMouseMove(0.2f, 0.2f));

                    Thread.sleep(500);
                    System.out.println("[TestClient -> InputTest] Gửi sự kiện di chuyển chuột (Bottom-Right)...");
                    eventSender.send(InputEventPacket.createMouseMove(0.8f, 0.8f));
                } catch (Exception e) {
                    System.err.println("[TestClient -> InputTest] Lỗi: " + e.getMessage());
                }
            }, "TestClient-InputTest").start();

            // 4. Nhận và đếm FRAME_DATA liên tục
            int frameCount = 0;
            long testStartTime = System.currentTimeMillis();

            while (!socket.isClosed() && frameCount < 20) {
                FramePacket frame = FramePacket.readFrom(dis);
                frameCount++;
                long now = System.currentTimeMillis();
                long latency = now - frame.getTimestamp();

                System.out.printf("[TestClient] Nhận Frame #%02d | Size: %d KB | Latency: %d ms\n",
                        frameCount,
                        frame.getLength() / 1024,
                        latency);
            }

            System.out.println("[TestClient] Đã nhận thành công " + frameCount + " frames liên tục không lỗi!");
            System.out.println("[TestClient] Gửi DISCONNECT để kết thúc test...");
            PacketBuilder.writeEmpty(dos, PacketType.DISCONNECT);
            Thread.sleep(300);
            socket.close();
            System.out.println("[TestClient] Hoàn thành kiểm thử thành công!");

        } catch (Exception e) {
            System.err.println("[TestClient] Lỗi: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
