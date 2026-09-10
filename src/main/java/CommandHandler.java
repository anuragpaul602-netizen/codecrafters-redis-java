import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Dispatches a parsed command (e.g. ["ECHO", "hey"]) to the right handler
// and returns the RESP-encoded reply Main should write back to the client.
public class CommandHandler {

  // Main.java uses one shared CommandHandler instance across all client
  // threads, so this map is read/written concurrently — ConcurrentHashMap
  // keeps that safe without us hand-rolling locking.
  private final Map<String, Entry> store = new ConcurrentHashMap<>();

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
        return handleSet(args);
      case "GET":
        return handleGet(args);
      default:
        return "-ERR unknown command '" + args[0] + "'\r\n";
    }
  }

  private String handleSet(String[] args) {
    if (args.length < 3) {
      return "-ERR wrong number of arguments for 'set' command\r\n";
    }

    Long expiryAt = null;
    // Optional trailing "PX <milliseconds>" option.
    if (args.length >= 5 && args[3].equalsIgnoreCase("PX")) {
      long millis;
      try {
        millis = Long.parseLong(args[4]);
      } catch (NumberFormatException e) {
        return "-ERR value is not an integer or out of range\r\n";
      }
      expiryAt = System.currentTimeMillis() + millis;
    }

    store.put(args[1], new Entry(args[2], expiryAt));
    return "+OK\r\n";
  }

  private String handleGet(String[] args) {
    if (args.length < 2) {
      return "-ERR wrong number of arguments for 'get' command\r\n";
    }

    Entry entry = store.get(args[1]);
    if (entry == null || entry.isExpired()) {
      // Lazy expiry: we never scheduled anything to delete this key, a read
      // past its deadline is what notices and evicts it.
      store.remove(args[1]);
      // Redis represents "key not found" as a null bulk string ($-1\r\n),
      // distinct from an empty string value (which would be $0\r\n\r\n).
      return "$-1\r\n";
    }
    return encodeBulkString(entry.value);
  }

  // Length is the byte count of the encoded value, not its char count —
  // matters once non-ASCII input shows up.
  private String encodeBulkString(String value) {
    int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
    return "$" + byteLength + "\r\n" + value + "\r\n";
  }

  // A stored value plus the absolute wall-clock time (epoch millis) at which
  // it should stop being visible. expiryAt == null means "no expiry set".
  private static class Entry {
    final String value;
    final Long expiryAt;

    Entry(String value, Long expiryAt) {
      this.value = value;
      this.expiryAt = expiryAt;
    }

    boolean isExpired() {
      return expiryAt != null && System.currentTimeMillis() >= expiryAt;
    }
  }
}
