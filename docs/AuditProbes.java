// Audit-only probes against compiled application classes. All fixtures stay under docs.
import com.jarves.mh.runtime.WorkspaceCheckpoints;
import com.jarves.mh.runtime.CodexJsonlParser;
import com.jarves.mh.runtime.task.*;
import com.jarves.mh.model.brain.*;
import com.jarves.mh.provider.ProviderEndpointNormalizer;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

class AuditProbes {
    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory(Path.of("docs"), "harness-audit-fixture-");
        try {
            WorkspaceCheckpoints store = new WorkspaceCheckpoints(fixture.toFile());
            File workspace = store.ensureWorkspace("audit");
            File original = new File(workspace, "large-original.bin");
            try (RandomAccessFile f = new RandomAccessFile(original, "rw")) {
                f.setLength(26L * 1024 * 1024);
            }
            Map<String, String> before = store.snapshot(workspace);
            store.createCheckpoint("audit", workspace);
            boolean backupExists = new File(store.checkpointDir("audit"), "project/large-original.bin").isFile();
            try (RandomAccessFile f = new RandomAccessFile(original, "rw")) {
                f.setLength(26L * 1024 * 1024 + 1);
            }
            List<String> changes = store.changedFiles(workspace, before);
            store.saveChangedPaths("audit", changes);
            boolean restored = store.restoreCheckpoint("audit", workspace, WorkspaceCheckpoints.DEFAULT_CHECKPOINT_TAG);
            System.out.println("UNDO: backupExists=" + backupExists + " changedPaths=" + changes
                + " restoreReturned=" + restored + " originalExistsAfterRestore=" + original.exists());
            if (backupExists || !changes.contains("large-original.bin") || original.exists()) throw new AssertionError("Undo probe did not reproduce");

            File emptyWorkspace = Files.createDirectory(fixture.resolve("verification")).toFile();
            String objective = "Create result.txt containing DONE";
            TaskDecompositionContext context = new TaskDecompositionContext("audit", "audit",
                Collections.emptyList(), List.of(objective), null, Collections.emptyList(), 20, Instant.EPOCH);
            ExecutionPlan plan = new DefaultTaskDecomposer().decompose("audit-task", objective, context);
            CanonicalTask task = new CanonicalTask("audit-task", "audit", "audit", objective,
                Collections.emptyList(), List.of(objective), plan, null, null,
                Collections.emptyList(), null, null, Instant.EPOCH, Instant.EPOCH);
            ExecutionStep step = plan.getSteps().get(0);
            StepVerificationResult verification = new DefaultStepVerifier().verify(task, step, emptyWorkspace);
            System.out.println("VERIFICATION: command=" + step.getVerificationCommand() + " passed=" + verification.getPassed()
                + " requestedFileExists=" + new File(emptyWorkspace, "result.txt").exists());
            if (!verification.getPassed() || new File(emptyWorkspace, "result.txt").exists()) throw new AssertionError("Verification probe did not reproduce");

            var runner = new ControlledStepCommandRunner();
            var small = runner.execute("printf 'done'", emptyWorkspace, 2L);
            // Shell builtins only: avoid leaving descendant processes after a timeout.
            String noisyCommand = "i=0; while [ $i -lt 20000 ]; do printf '0123456789012345678901234567890123456789012345678901234567890123456789'; i=$((i+1)); done";
            File controlOutput = new File(emptyWorkspace, "noisy-control.log");
            long started = System.nanoTime();
            Process control = new ProcessBuilder("/bin/sh", "-c", noisyCommand)
                .directory(emptyWorkspace).redirectErrorStream(true).redirectOutput(controlOutput).start();
            if (!control.waitFor(10, TimeUnit.SECONDS)) {
                control.destroyForcibly();
                throw new AssertionError("Control command timed out");
            }
            long controlMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            var noisy = runner.execute(noisyCommand, emptyWorkspace, 2L);
            System.out.println("PIPE: smallExit=" + small.getFirst() + " fileControlExit=" + control.exitValue()
                + " fileControlMs=" + controlMs + " outputBytes=" + controlOutput.length()
                + " noisyExit=" + noisy.getFirst() + " noisyMessage=" + noisy.getSecond());
            if (small.getFirst() != 0 || control.exitValue() != 0 || controlMs >= 2000
                || !noisy.getSecond().contains("timed out")) throw new AssertionError("Pipe probe did not reproduce");

            var endpoint = ProviderEndpointNormalizer.INSTANCE.normalize("https://proxy.example/v1", "anthropic-messages");
            System.out.println("PROTOCOL: requested=anthropic-messages actual=" + endpoint.getApi());
            if (endpoint.getApi().equals("anthropic-messages")) throw new AssertionError("Protocol probe did not reproduce");

            String item = "\"item\":{\"id\":\"m1\",\"type\":\"agent_message\",\"text\":\"hello\"}";
            var updated = CodexJsonlParser.INSTANCE.parseLine("{\"type\":\"item.updated\"," + item + "}");
            var completed = CodexJsonlParser.INSTANCE.parseLine("{\"type\":\"item.completed\"," + item + "}");
            System.out.println("CODEX TEXT: updated=" + updated + " completed=" + completed);
            if (!updated.toString().equals("Ignored") || !completed.toString().contains("hello")) throw new AssertionError("Codex probe did not reproduce");
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
