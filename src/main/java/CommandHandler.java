import java.nio.charset.StandardCharsets;

// Dispatches a parsed command (e.g. ["ECHO", "hey"]) to the right handler
// and returns the RESP-encoded reply Main should write back to the client.
public class CommandHandler {

  public String handle(String[] args) {
    if (args.length == 0) {
      return "";
    }

    String command = args[0].toUpperCase();
    switch (command) {
      case "PING":
        return "+PONG\r\n";
      case "ECHO":
        if (args.length < 2) {
          return "-ERR wrong number of arguments for 'echo' command\r\n";
        }
        return encodeBulkString(args[1]);
      default:
        return "-ERR unknown command '" + args[0] + "'\r\n";
    }
  }

  // Length is the byte count of the encoded value, not its char count —
  // matters once non-ASCII input shows up.
  private String encodeBulkString(String value) {
    int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
    return "$" + byteLength + "\r\n" + value + "\r\n";
  }
}
