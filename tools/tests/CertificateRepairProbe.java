import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import com.jarves.mh.runtime.RuntimeCertificates;
class CertificateRepairProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(".git/tls-probe/root");
        Path bundle = root.resolve("etc/ssl/certs/ca-certificates.crt");
        Files.createDirectories(bundle.getParent());
        Files.copy(Path.of("/etc/ssl/certs/ca-certificates.crt"), bundle, StandardCopyOption.REPLACE_EXISTING);
        if (!RuntimeCertificates.INSTANCE.repair(root.toFile())) throw new AssertionError("Certificate repair failed");
        if (!RuntimeCertificates.INSTANCE.repair(root.toFile())) throw new AssertionError("Repeated repair failed");
        System.out.println("VERIFIED production certificate repair and repeated invocation using the installed public CA bundle");
    }
}
