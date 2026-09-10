import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Main {
  // Stateless, so one shared instance is safe to call from every client thread.
  private static final CommandHandler commandHandler = new CommandHandler();

  public static void main(String[] args) {
    System.out.println("Logs from your program will appear here!");

    int port = 6379;

    try (ServerSocket serverSocket = new ServerSocket(port)) {
      // Since the tester restarts your program quite often, setting SO_REUSEADDR
      // ensures that we don't run into 'Address already in use' errors
      serverSocket.setReuseAddress(true);

      // Keep looping forever: each time round, wait for ONE new client to
      // connect, then immediately go back to waiting for the next one.
      while (true) {
        Socket clientSocket = serverSocket.accept();
        // Don't handle this client here on the main thread — that would
        // block us from accepting anyone else until this client disconnects.
        // Instead, hand the connection to a brand new thread and let the
        // main thread go straight back to accept().
        new Thread(() -> handleClient(clientSocket)).start();
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  // Runs on its own dedicated thread — one call to this method per connected client.
  private static void handleClient(Socket clientSocket) {
    try (clientSocket;
         InputStream input = new BufferedInputStream(clientSocket.getInputStream());
         OutputStream output = clientSocket.getOutputStream()) {

      List<String> command;
      // Keep reading commands off this connection until the client closes
      // it — readCommand() returns null once there's nothing left to read.
      while ((command = readCommand(input)) != null) {
        if (command.isEmpty()) {
          continue;
        }
        String response = commandHandler.handle(command.toArray(new String[0]));
        output.write(response.getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  // ---- RESP parsing ----
  // The bytes coming in are RESP-encoded, not plain text — e.g. `ECHO hey`
  // arrives as *2\r\n$4\r\nECHO\r\n$3\r\nhey\r\n. These three methods turn
  // that into the String[] tokens CommandHandler.handle() expects.

  // Reads one full client command and returns it as e.g. ["ECHO", "hey"].
  // Returns null if the client closed the connection before sending anything.
  private static List<String> readCommand(InputStream in) throws IOException {
    String line = readLine(in);
    if (line == null) {
      return null; // client disconnected cleanly
    }
    if (line.isEmpty() || line.charAt(0) != '*') {
      throw new IOException("Expected RESP array (*), got: " + line);
    }

    int argCount = Integer.parseInt(line.substring(1).trim());
    List<String> args = new ArrayList<>(argCount);
    for (int i = 0; i < argCount; i++) {
      args.add(readBulkString(in));
    }
    return args;
  }

  // Reads one "$<length>\r\n<bytes>\r\n" bulk string and returns <bytes> as a String.
  private static String readBulkString(InputStream in) throws IOException {
    String header = readLine(in);
    if (header == null || header.isEmpty() || header.charAt(0) != '$') {
      throw new IOException("Expected bulk string header ($), got: " + header);
    }
    int length = Integer.parseInt(header.substring(1).trim());

    // Bulk strings are binary-safe, so we read exactly `length` raw bytes —
    // we never scan the payload itself looking for a \r\n delimiter.
    byte[] data = new byte[length];
    int totalRead = 0;
    while (totalRead < length) {
      int bytesRead = in.read(data, totalRead, length - totalRead);
      if (bytesRead == -1) {
        throw new IOException("Unexpected end of stream while reading bulk string");
      }
      totalRead += bytesRead;
    }
    readLine(in); // consume the trailing \r\n that follows the value

    return new String(data, StandardCharsets.UTF_8);
  }

  // Reads bytes up to (and consuming) the next \r\n, returned without the \r\n.
  // Returns null if the stream had already ended before this call.
  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    boolean sawAnyByte = false;
    int b;
    while ((b = in.read()) != -1) {
      sawAnyByte = true;
      if (b == '\r') {
        in.read(); // consume the matching '\n'
        break;
      }
      line.write(b);
    }
    return sawAnyByte ? line.toString(StandardCharsets.UTF_8) : null;
  }
}