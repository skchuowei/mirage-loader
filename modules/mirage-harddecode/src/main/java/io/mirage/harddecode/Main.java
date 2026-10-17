package io.mirage.harddecode;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipFile;

/**
 * CLI 入口：java -cp ... io.mirage.harddecode.Main &lt;mod.jar&gt; [-o output.json]
 *
 * <p>用 Gradle application 插件跑时等价于：
 * gradlew :mirage-harddecode:run --args="&lt;jar&gt; -o &lt;out&gt;"
 */
public class Main {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: java -cp ... io.mirage.harddecode.Main <mod.jar> [-o output.json]");
            System.err.println("  缺省 -o 时打印到 stdout。");
            System.exit(2);
        }

        String jarPath = args[0];
        String outPath = null;
        for (int i = 1; i < args.length; i++) {
            if ("-o".equals(args[i]) && i + 1 < args.length) {
                outPath = args[++i];
            }
        }

        Path jar = Paths.get(jarPath);
        if (!Files.isRegularFile(jar)) {
            System.err.println("错误：jar 不存在: " + jar.toAbsolutePath());
            System.exit(1);
        }

        OutputModel model = new OutputModel();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            new JarAnalyzer(zip, model).analyze();
        } catch (IOException e) {
            System.err.println("分析失败: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String json = gson.toJson(model);

        try {
            if (outPath != null) {
                Path out = Paths.get(outPath);
                if (out.getParent() != null) Files.createDirectories(out.getParent());
                // UTF-8 无 BOM
                try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                    w.write(json);
                }
                System.err.println("已写出: " + out.toAbsolutePath());
                System.err.println(String.format("  classes=%d methods=%d calls=%d unresolved=%d",
                    model._diagnostics.stats.classCount, model._diagnostics.stats.methodCount,
                    model._diagnostics.stats.methodCallCount, model._diagnostics.stats.unresolvedCount));
                System.err.println("  mod.id=" + model.mod.id + "  version=" + model.mod.version);
            } else {
                System.out.println(json);
            }
        } catch (IOException e) {
            System.err.println("写输出失败: " + e.getMessage());
            System.exit(1);
        }
    }
}
