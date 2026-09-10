import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Dispatches a parsed command (e.g. ["ECHO", "hey"]) to the right handler
// and returns the RESP-encoded reply Main should write back to the client.
public class CommandHandler {

  // Main.java uses one shared CommandHandler instance across all client
  // threads, so this map is read/written concurrently — ConcurrentHashMap
  // keeps that safe without us hand-rolling locking.
  private final Map<String, String> store = new ConcurrentHashMap<>();

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
      case "SET":
        if (args.length < 3) {
          return "-ERR wrong number of arguments for 'set' command\r\n";
        }
        store.put(args[1], args[2]);
        return "+OK\r\n";
      case "GET":
        if (args.length < 2) {
          return "-ERR wrong number of arguments for 'get' command\r\n";
        }
        String value = store.get(args[1]);
        // Redis represents "key not found" as a null bulk string ($-1\r\n),
        // distinct from an empty string value (which would be $0\r\n\r\n).
        return value == null ? "$-1\r\n" : encodeBulkString(value);
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
