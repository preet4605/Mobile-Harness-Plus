import com.jarves.mh.runtime.GuestSkillAccess;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded reproduction against the compiled production helper, without an Android runtime. */
class SkillCacheProbe {
    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory(Path.of(".git"), "skill-cache-probe-");
        try {
            File source = fixture.resolve("source").toFile(); source.mkdir();
            File skill = new File(source, "SKILL.md"); Files.writeString(skill.toPath(), "skill body");
            File cache = fixture.resolve("cache").toFile();
            GuestSkillAccess access = new GuestSkillAccess(fixture.toFile());
            var snapshot = GuestSkillAccess.class.getDeclaredMethod("snapshot", File.class, File.class);
            snapshot.setAccessible(true);
            String first = (String) snapshot.invoke(access, skill, cache);
            File published = new File(cache, first.replace("/opt/pocket/skills/linked/", "")).getParentFile();
            Path outside = Files.createDirectory(fixture.resolve("outside"));
            try (var file = new RandomAccessFile(outside.resolve("external-data").toFile(), "rw")) {
                file.setLength(64L * 1024 * 1024);
            }
            Files.createSymbolicLink(new File(published, "guest-link").toPath(), outside.toAbsolutePath());
            String repaired = (String) snapshot.invoke(access, skill, cache);
            if (repaired.equals(first)) throw new AssertionError("Changed snapshot was reused");
            System.out.println("VERIFIED fresh snapshot without traversing a guest-created cache symlink");
        } catch (InvocationTargetException error) {
            throw new AssertionError("Guest cache links made a valid original skill unavailable", error.getCause());
        } finally {
            try (var files = Files.walk(fixture)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
