package io.lmctl.lmcode.examples;

import io.lmctl.lmcode.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A host dispatches annotated entries and consumes their interface return value. */
public final class RuleEntries {
  private RuleEntries() {}
  public static void main(String[] args) throws Exception {
    Registry registry = new Registry(false);
    Context context = new Context();
    Program program = Program.load(Files.readAllBytes(Path.of(args[0])),
        Files.readAllBytes(Path.of(args[1])), registry);
    program.run(context, registry);
    for (int i = 0; i < program.entries().size(); i++) {
      if (program.entries().get(i).annotation().equals("increment")) {
        Value result = program.call(i, context, registry, List.of(Value.ofInt(41)));
        System.out.println("increment = " + result.asInt());
      }
    }
  }
}
