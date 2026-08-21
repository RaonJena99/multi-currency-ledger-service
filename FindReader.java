import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipFile;

public class FindReader {
    public static void main(String[] args) throws Exception {
        Path start = Paths.get("C:\\Users\\SSAFY\\.gradle\\caches\\modules-2\\files-2.1\\org.springframework.batch");
        Files.walk(start)
            .filter(p -> p.toString().endsWith(".jar") && p.toString().contains("6.0.3"))
            .forEach(p -> {
                try {
                    ZipFile zf = new ZipFile(p.toFile());
                    zf.stream().filter(e -> e.getName().endsWith("ItemReader.class") || e.getName().endsWith("Chunk.class"))
                        .forEach(e -> System.out.println(p.getFileName() + " -> " + e.getName()));
                    zf.close();
                } catch (Exception ex) {}
            });
    }
}
