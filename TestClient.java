import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class TestClient {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Nhập Session ID: ");
        String sessionId = scanner.nextLine().trim();

        System.out.print("Nhập Passcode: ");
        String passcode = scanner.nextLine().trim();

        try (Socket socket = new Socket("127.0.0.1", 8888);
                DataInputStream dis = new DataInputStream(socket.getInputStream());
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

            System.out.println("[TestClient] Connected to Server!");

            // 1. Send AUTH_REQ (Type = 0x01)
            String authPayload = sessionId + "|" + passcode;
            byte[] authBytes = authPayload.getBytes(StandardCharsets.UTF_8);
            dos.writeByte(0x01); // Type
            dos.writeInt(authBytes.length); // Length
            dos.write(authBytes); // Value
            dos.flush();
            System.out.println("[TestClient] Sent AUTH_REQ: " + authPayload);

            // 2. Read AUTH_RESP (Type = 0x02)
            byte respType = dis.readByte();
            int respLen = dis.readInt();
            byte[] respVal = new byte[respLen];
            dis.readFully(respVal);

            if (respType == 0x02 && respVal.length > 0 && respVal[0] == 1) {
                System.out.println("[TestClient] AUTH_RESP: SUCCESS");

                // 3. Expect FRAME_DATA (Type = 0x03)
                byte frameType = dis.readByte();
                int frameLen = dis.readInt();
                System.out.println(
                        "[TestClient] Received packet type: " + frameType + ", length: " + frameLen + " bytes");

                if (frameType == 0x03) {
                    byte[] frameBytes = new byte[frameLen];
                    dis.readFully(frameBytes);
                    System.out.println("[TestClient] Successfully received FRAME_DATA (" + frameBytes.length
                            + " bytes). Protocol is WORKING!");
                } else {
                    System.out.println("[TestClient] Expected FRAME_DATA (3) but got: " + frameType);
                }
            } else {
                System.out.println("[TestClient] AUTH_RESP: FAILED (type=" + respType + ")");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
