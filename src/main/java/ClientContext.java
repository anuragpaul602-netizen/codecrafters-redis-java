import java.util.ArrayList;
import java.util.List;

// Per-connection transaction state — each client thread (see Main.java) owns
// its own instance, so MULTI/EXEC on one connection never affects another.
public class ClientContext {
  boolean inTransaction = false;
  List<String[]> queuedCommands = new ArrayList<>();
}
