import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// Dispatches a parsed command (e.g. ["ECHO", "hey"]) to the right handler
// and returns the RESP-encoded reply Main should write back to the client.
public class CommandHandler {

  // Main.java uses one shared CommandHandler instance across all client
  // threads, so these maps are read/written concurrently — ConcurrentHashMap
  // keeps that safe without us hand-rolling locking.
  private final Map<String, Entry> store = new ConcurrentHashMap<>();
  private final Map<String, List<String>> lists = new ConcurrentHashMap<>();
  private final Map<String, List<StreamEntry>> streams = new ConcurrentHashMap<>();

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
      case "RPUSH":
        return handleRpush(args);
      case "LPUSH":
        return handleLpush(args);
      case "LLEN":
        return handleLlen(args);
      case "LPOP":
        return handleLpop(args);
      case "BLPOP":
        return handleBlpop(args);
      case "LRANGE":
        return handleLrange(args);
      case "TYPE":
        return handleType(args);
      case "XADD":
        return handleXadd(args);
      case "XRANGE":
        return handleXrange(args);
      case "XREAD":
        return handleXread(args);
      default:
        return "-ERR unknown command '" + args[0] + "'\r\n";
    }
  }

  private String handleRpush(String[] args) {
    if (args.length < 3) {
      return "-ERR wrong number of arguments for 'rpush' command\r\n";
    }

    List<String> list = lists.computeIfAbsent(args[1], key -> new CopyOnWriteArrayList<>());
    for (int i = 2; i < args.length; i++) {
      list.add(args[i]);
    }
    return ":" + list.size() + "\r\n";
  }

  private String handleLpush(String[] args) {
    if (args.length < 3) {
      return "-ERR wrong number of arguments for 'lpush' command\r\n";
    }

    List<String> list = lists.computeIfAbsent(args[1], key -> new CopyOnWriteArrayList<>());
    // Each value in turn goes to the front, so the last argument ends up
    // as the new head of the list.
    for (int i = 2; i < args.length; i++) {
      list.add(0, args[i]);
    }
    return ":" + list.size() + "\r\n";
  }

  private String handleLlen(String[] args) {
    if (args.length < 2) {
      return "-ERR wrong number of arguments for 'llen' command\r\n";
    }
    List<String> list = lists.getOrDefault(args[1], List.of());
    return ":" + list.size() + "\r\n";
  }

  private String handleLpop(String[] args) {
    if (args.length < 2) {
      return "-ERR wrong number of arguments for 'lpop' command\r\n";
    }
    List<String> list = lists.get(args[1]);

    if (args.length == 2) {
      if (list == null || list.isEmpty()) {
        return "$-1\r\n";
      }
      return encodeBulkString(list.remove(0));
    }

    int count;
    try {
      count = Integer.parseInt(args[2]);
    } catch (NumberFormatException e) {
      return "-ERR value is not an integer or out of range\r\n";
    }
    if (list == null || list.isEmpty() || count <= 0) {
      return "*0\r\n";
    }

    int popCount = Math.min(count, list.size());
    StringBuilder response = new StringBuilder();
    response.append('*').append(popCount).append("\r\n");
    for (int i = 0; i < popCount; i++) {
      response.append(encodeBulkString(list.remove(0)));
    }
    return response.toString();
  }

  private String handleType(String[] args) {
    if (args.length < 2) {
      return "-ERR wrong number of arguments for 'type' command\r\n";
    }
    String key = args[1];

    Entry entry = store.get(key);
    if (entry != null && !entry.isExpired()) {
      return "+string\r\n";
    }
    if (lists.containsKey(key)) {
      return "+list\r\n";
    }
    if (streams.containsKey(key)) {
      return "+stream\r\n";
    }
    return "+none\r\n";
  }

  private String handleXadd(String[] args) {
    // args[1]=key, args[2]=id, then field/value pairs — needs an odd count
    // beyond that (at least one pair).
    if (args.length < 5 || (args.length - 3) % 2 != 0) {
      return "-ERR wrong number of arguments for 'xadd' command\r\n";
    }

    String key = args[1];
    String rawId = args[2];
    List<StreamEntry> stream = streams.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
    long[] lastId = stream.isEmpty() ? null : parseStreamId(stream.get(stream.size() - 1).id);

    String id;
    try {
      id = resolveXaddId(rawId, lastId);
    } catch (NumberFormatException e) {
      return "-ERR Invalid stream ID specified as stream command argument\r\n";
    }

    long[] idParts = parseStreamId(id);
    if (idParts[0] == 0 && idParts[1] == 0) {
      return "-ERR The ID specified in XADD must be greater than 0-0\r\n";
    }
    if (lastId != null) {
      boolean isGreater = idParts[0] > lastId[0]
          || (idParts[0] == lastId[0] && idParts[1] > lastId[1]);
      if (!isGreater) {
        return "-ERR The ID specified in XADD is equal or smaller than the target stream top item\r\n";
      }
    }

    List<String> fieldsAndValues = new ArrayList<>();
    for (int i = 3; i < args.length; i++) {
      fieldsAndValues.add(args[i]);
    }
    stream.add(new StreamEntry(id, fieldsAndValues));
    return encodeBulkString(id);
  }

  // Turns "*" (fully auto) or "<ms>-*" (partially auto) into a concrete
  // "<ms>-<seq>" id; an already-explicit id passes through unchanged.
  private String resolveXaddId(String rawId, long[] lastId) {
    if (rawId.equals("*")) {
      return autoSeqId(System.currentTimeMillis(), lastId);
    }
    if (rawId.endsWith("-*")) {
      long ms = Long.parseLong(rawId.substring(0, rawId.length() - 2));
      return autoSeqId(ms, lastId);
    }
    return rawId;
  }

  // Sequence defaults to last+1 within the same millisecond, otherwise 0 —
  // except ms 0 starts at 1, since 0-0 itself is a reserved, invalid id.
  private String autoSeqId(long ms, long[] lastId) {
    long seq;
    if (lastId != null && lastId[0] == ms) {
      seq = lastId[1] + 1;
    } else {
      seq = ms == 0 ? 1 : 0;
    }
    return ms + "-" + seq;
  }

  // Splits "<ms>-<seq>" into its two numeric parts.
  private long[] parseStreamId(String id) {
    String[] parts = id.split("-", 2);
    return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])};
  }

  private String handleXrange(String[] args) {
    if (args.length < 4) {
      return "-ERR wrong number of arguments for 'xrange' command\r\n";
    }

    List<StreamEntry> stream = streams.getOrDefault(args[1], List.of());
    long[] start = parseRangeBound(args[2], true);
    long[] end = parseRangeBound(args[3], false);

    List<StreamEntry> matched = new ArrayList<>();
    for (StreamEntry entry : stream) {
      long[] id = parseStreamId(entry.id);
      if (compareIds(id, start) >= 0 && compareIds(id, end) <= 0) {
        matched.add(entry);
      }
    }

    StringBuilder response = new StringBuilder();
    response.append('*').append(matched.size()).append("\r\n");
    for (StreamEntry entry : matched) {
      response.append("*2\r\n").append(encodeBulkString(entry.id));
      response.append('*').append(entry.fieldsAndValues.size()).append("\r\n");
      for (String fieldOrValue : entry.fieldsAndValues) {
        response.append(encodeBulkString(fieldOrValue));
      }
    }
    return response.toString();
  }

  // "-"/"+" mean smallest/largest possible id; a bare "<ms>" (no "-<seq>")
  // defaults its missing seq to 0 for a start bound, or max for an end bound.
  private long[] parseRangeBound(String bound, boolean isStart) {
    if (bound.equals("-")) {
      return new long[] {0, 0};
    }
    if (bound.equals("+")) {
      return new long[] {Long.MAX_VALUE, Long.MAX_VALUE};
    }
    if (bound.contains("-")) {
      return parseStreamId(bound);
    }
    return new long[] {Long.parseLong(bound), isStart ? 0 : Long.MAX_VALUE};
  }

  private int compareIds(long[] a, long[] b) {
    return a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]);
  }

  private String handleXread(String[] args) {
    int streamsIdx = -1;
    for (int i = 1; i < args.length; i++) {
      if (args[i].equalsIgnoreCase("STREAMS")) {
        streamsIdx = i;
        break;
      }
    }
    if (streamsIdx == -1) {
      return "-ERR syntax error\r\n";
    }

    // Everything after STREAMS is "key1 key2 ... id1 id2 ..." — an equal
    // split, keys first then their matching start-ids.
    int remaining = args.length - streamsIdx - 1;
    if (remaining == 0 || remaining % 2 != 0) {
      return "-ERR Unbalanced XREAD list of streams: for each stream key an ID or '$' must be specified.\r\n";
    }
    int numStreams = remaining / 2;

    List<String> resultBlocks = new ArrayList<>();
    for (int i = 0; i < numStreams; i++) {
      String key = args[streamsIdx + 1 + i];
      long[] afterId = parseStreamId(args[streamsIdx + 1 + numStreams + i]);

      List<StreamEntry> matched = new ArrayList<>();
      for (StreamEntry entry : streams.getOrDefault(key, List.of())) {
        if (compareIds(parseStreamId(entry.id), afterId) > 0) {
          matched.add(entry);
        }
      }
      // A stream with nothing new is left out of the result entirely, not
      // included with an empty entry list.
      if (!matched.isEmpty()) {
        resultBlocks.add(encodeXreadStreamBlock(key, matched));
      }
    }

    if (resultBlocks.isEmpty()) {
      return "*-1\r\n";
    }
    StringBuilder response = new StringBuilder();
    response.append('*').append(resultBlocks.size()).append("\r\n");
    for (String block : resultBlocks) {
      response.append(block);
    }
    return response.toString();
  }

  private String encodeXreadStreamBlock(String key, List<StreamEntry> matched) {
    StringBuilder block = new StringBuilder();
    block.append("*2\r\n").append(encodeBulkString(key));
    block.append('*').append(matched.size()).append("\r\n");
    for (StreamEntry entry : matched) {
      block.append("*2\r\n").append(encodeBulkString(entry.id));
      block.append('*').append(entry.fieldsAndValues.size()).append("\r\n");
      for (String fieldOrValue : entry.fieldsAndValues) {
        block.append(encodeBulkString(fieldOrValue));
      }
    }
    return block.toString();
  }

  private String handleBlpop(String[] args) {
    if (args.length < 3) {
      return "-ERR wrong number of arguments for 'blpop' command\r\n";
    }

    String key = args[1];
    double timeoutSeconds;
    try {
      timeoutSeconds = Double.parseDouble(args[2]);
    } catch (NumberFormatException e) {
      return "-ERR timeout is not a float or out of range\r\n";
    }
    // timeout 0 means block forever, so there's no deadline to compare against.
    long deadline = timeoutSeconds > 0 ? System.currentTimeMillis() + (long) (timeoutSeconds * 1000) : -1;

    // Each client already runs on its own dedicated thread (see Main.java),
    // so we can just have this thread poll and sleep instead of needing any
    // async/notify machinery.
    while (true) {
      List<String> list = lists.get(key);
      if (list != null && !list.isEmpty()) {
        try {
          String value = list.remove(0);
          return "*2\r\n" + encodeBulkString(key) + encodeBulkString(value);
        } catch (IndexOutOfBoundsException e) {
          // Another thread popped the last element between our isEmpty()
          // check and remove(0) — just fall through and keep polling.
        }
      }
      if (deadline != -1 && System.currentTimeMillis() >= deadline) {
        return "*-1\r\n";
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return "*-1\r\n";
      }
    }
  }

  private String handleLrange(String[] args) {
    if (args.length < 4) {
      return "-ERR wrong number of arguments for 'lrange' command\r\n";
    }

    int start;
    int stop;
    try {
      start = Integer.parseInt(args[2]);
      stop = Integer.parseInt(args[3]);
    } catch (NumberFormatException e) {
      return "-ERR value is not an integer or out of range\r\n";
    }

    // A key with no list is treated as an empty list, not an error.
    List<String> list = lists.getOrDefault(args[1], List.of());
    int size = list.size();

    // Negative indexes count from the end (-1 is the last element), same as
    // Python slicing; still-negative after that just clamps to the start.
    if (start < 0) {
      start = Math.max(size + start, 0);
    }
    if (stop < 0) {
      stop = size + stop;
    }

    // Clamp stop into range; an out-of-range start just yields no elements
    // once compared against the clamped stop below.
    if (stop >= size) {
      stop = size - 1;
    }
    if (start > stop || size == 0) {
      return "*0\r\n";
    }

    List<String> slice = list.subList(start, stop + 1);
    StringBuilder response = new StringBuilder();
    response.append('*').append(slice.size()).append("\r\n");
    for (String element : slice) {
      response.append(encodeBulkString(element));
    }
    return response.toString();
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

  // One stream entry: its "<ms>-<seq>" ID plus a flat field1, value1, field2,
  // value2... list.
  private static class StreamEntry {
    final String id;
    final List<String> fieldsAndValues;

    StreamEntry(String id, List<String> fieldsAndValues) {
      this.id = id;
      this.fieldsAndValues = fieldsAndValues;
    }
  }
}
