package probe;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P4 门禁：批量加载一个 fake pack 里的<b>全部</b>类，按错误种类计数。
 *
 * <p><b>为什么要单独做这一步</b>：{@code ClassFormatError} / {@code VerifyError}
 * 是「生成器写坏了字节码」，属于我们的 bug，必须为 0；
 * 而 {@code NoClassDefFoundError} / {@code ClassNotFoundException} 通常是「缺的是
 * {@code net.minecraft.*}（本轮明确不生成）」，属于预期。把两者混在一个"失败数"里
 * 就永远说不清到底是谁的问题。
 *
 * <p>输出契约（ASCII）：
 * <pre>
 * PACK total=N ok=N formatErr=N verifyErr=N missing=N other=N
 * </pre>
 */
public final class PackLoadProbe {

    public static void main(String[] args) throws Exception {
        Path pack = Paths.get(args[0]);
        List<URL> urls = new ArrayList<>();
        urls.add(pack.toUri().toURL());
        for (int i = 1; i < args.length; i++) {
            Path p = Paths.get(args[i]);
            if (Files.isDirectory(p)) urls.add(p.toUri().toURL());
        }
        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader());

        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(pack)) {
            s.filter(p -> p.toString().endsWith(".class")).forEach(files::add);
        }
        files.sort(Comparator.naturalOrder());

        int ok = 0;
        int formatErr = 0;
        int verifyErr = 0;
        int missing = 0;
        int other = 0;
        Map<String, Integer> detail = new LinkedHashMap<>();
        for (Path p : files) {
            String name = pack.relativize(p).toString().replace(File.separatorChar, '/');
            name = name.substring(0, name.length() - 6).replace('/', '.');
            try {
                Class<?> c = Class.forName(name, false, cl);
                // 链接（解析超类/接口/字段/方法签名）才会暴露 ClassFormatError / VerifyError
                c.getDeclaredMethods();
                c.getDeclaredFields();
                ok++;
            } catch (VerifyError e) {
                verifyErr++;
                detail.merge("BAD " + name + " <- " + e.getClass().getSimpleName()
                        + " :: " + oneLine(e.getMessage()), 1, Integer::sum);
            } catch (ClassFormatError e) {
                // UnsupportedClassVersionError 是 ClassFormatError 的子类，一并落这里
                formatErr++;
                detail.merge("BAD " + name + " <- " + e.getClass().getSimpleName()
                        + " :: " + oneLine(e.getMessage()), 1, Integer::sum);
            } catch (NoClassDefFoundError | ClassNotFoundException e) {
                missing++;
            } catch (Throwable t) {
                other++;
                detail.merge("OTHER " + name + " <- " + t.getClass().getSimpleName(), 1, Integer::sum);
            }
        }
        System.out.println("PACK total=" + files.size() + " ok=" + ok
                + " formatErr=" + formatErr + " verifyErr=" + verifyErr
                + " missing=" + missing + " other=" + other);
        detail.forEach((k, v) -> System.out.println("DETAIL x" + v + " " + k));
    }

    private static String oneLine(String s) {
        if (s == null) return "-";
        String x = s.replace('\n', ' ').replace('\r', ' ');
        return x.length() > 160 ? x.substring(0, 160) : x;
    }
}
