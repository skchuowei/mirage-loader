package io.mirage.mgscript;

import io.mirage.mgscript.ast.Ast;
import io.mirage.mgscript.parse.Parser;
import io.mirage.mgscript.runtime.EngineHost;
import io.mirage.mgscript.runtime.Interp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MGScript 门面：编译 + 执行。
 *
 * <p>一个 mod = 一个 {@link Interp}（共享模块作用域，所以 {@code shared} 变量跨文件可见），
 * 目录下的 {@code *.mgs} 按文件名排序依次执行。排序而不是随机，是为了让
 * 「item 声明在前、命令注册在后」这类顺序依赖稳定可复现。
 */
public final class ScriptEngine {

    private ScriptEngine() {}

    /** 编译单个文件（不执行），解析失败抛 {@link ScriptError}。 */
    public static Ast.Block compile(String fileLabel, String source) {
        return new Parser(fileLabel, source).parseProgram();
    }

    /** 执行单个文件。 */
    public static Interp runSource(String fileLabel, String source, EngineHost host) {
        Interp interp = new Interp(host, fileLabel);
        interp.run(compile(fileLabel, source));
        return interp;
    }

    /**
     * 执行一个 mod 的脚本目录：收集 {@code *.mgs}（只扫一层，不递归——避免把备份目录吃进来），
     * 按文件名排序后共用同一个 {@link Interp} 执行。
     *
     * @return 执行完的 Interp；宿主保存它，事件回调时调 {@link Interp#invokeCallback}
     */
    public static Interp runDirectory(Path dir, EngineHost host) throws IOException {
        List<Path> files = listScripts(dir);
        if (files.isEmpty()) {
            throw new IOException("脚本目录里没有 .mgs 文件: " + dir);
        }
        Interp interp = new Interp(host, dir.toString());
        for (Path f : files) {
            String source = Files.readString(f, StandardCharsets.UTF_8);
            // 文件标签用相对路径，日志里短且能直接双击定位
            String label = friendlyLabel(f);
            try {
                interp.run(compile(label, source));
            } catch (ScriptError e) {
                throw e;
            }
        }
        return interp;
    }

    /** 返回 {@code dir} 下所有 {@code .mgs}，按文件名（不区分大小写）排序。 */
    public static List<Path> listScripts(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) return files;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> Files.isRegularFile(p))
                  .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mgs"))
                  .sorted((a, b) -> a.getFileName().toString()
                          .compareToIgnoreCase(b.getFileName().toString()))
                  .forEach(files::add);
        }
        return files;
    }

    /** 把 {@code D:\...\mods\hello\scripts\main.mgs} 显示成 {@code mods/hello/scripts/main.mgs}。 */
    public static String friendlyLabel(Path file) {
        String s = file.toString().replace('\\', '/');
        int i = s.indexOf("/mods/");
        return i >= 0 ? s.substring(i + 1) : file.getFileName().toString();
    }

    /** 渲染一条错误：摘要行 + 源码块 + 提示。宿主拿这个直接进日志。 */
    public static String describe(ScriptError e) {
        return e.summary() + e.diagnose();
    }

    /** 供宿主在汇总里用：把属性表渲染成紧凑的 {@code k=v}。 */
    public static String propsToString(Map<String, Object> props) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
