package io.mirage.mgscript;

import java.util.ArrayList;
import java.util.List;

/**
 * MGScript 统一错误：解析期与运行期共用。
 *
 * <p>设计目标（见 docs/plans/2026-10-31_mgscript-dsl.md §5）：一条错误必须给出三件事——
 * <b>位置</b>（file:line:col）、<b>源码行 + 插入符</b>、<b>修法</b>（候选名 / 提示）。
 * 这是自研解释器相对「嵌入 Rhino/GraalJS」的核心优势：对方只能把 JS 堆栈翻译成 Java 堆栈。
 *
 * <p>渲染样例：
 * <pre>
 * mods/hello-mgs/scripts/main.mgs:12:5  未知事件 "player_jon"
 *   12 | on player_jon(p)
 *      |    ^^^^^^^^^^
 *   候选: player_join, player_leave, player_tick
 * </pre>
 *
 * <p>不在此类里打日志（引擎不该决定日志级别），由 {@code ScriptHost} 统一走 {@code MirageLog}。
 */
public final class ScriptError extends RuntimeException {

    private final String file;
    private final int line;
    private final int col;
    private final int length;
    private final String sourceLine;
    private final List<String> hints;

    private ScriptError(String file, int line, int col, int length,
                        String sourceLine, String message, List<String> hints, Throwable cause) {
        super(message, cause);
        this.file = file;
        this.line = line;
        this.col = col;
        this.length = length;
        this.sourceLine = sourceLine;
        this.hints = hints;
    }

    /** 带源码行的错误（解析期用；lines 是 1 起的源码行数组，越界自动降级）。 */
    public static ScriptError at(String file, String[] lines, int line, int col, int length,
                                 String message, String... hints) {
        String src = null;
        if (lines != null && line >= 1 && line <= lines.length) src = lines[line - 1];
        return new ScriptError(file, line, col, Math.max(1, length), src, message, List.of(hints), null);
    }

    /** 无源码行（运行期用：AST 只带位置）。 */
    public static ScriptError at(String file, int line, int col, String message, Throwable cause) {
        return new ScriptError(file, line, col, 1, null, message, List.of(), cause);
    }

    public String file()   { return file; }
    public int line()      { return line; }
    public int col()       { return col; }

    /**
     * 渲染源码块 + 提示。运行期错误通常只有行号没有源码行（AST 不存原文），
     * 此时仍然必须把 hints 打出来——「是不是想写 player」这类候选正是运行期才有的信息。
     */
    public String diagnose() {
        StringBuilder sb = new StringBuilder();
        if (sourceLine != null) {
            String lineNo = String.valueOf(line);
            String pad = " ".repeat(Math.max(0, 4 - lineNo.length()));
            // 源码行宽度 = pad(≤4) + 行号 + " | " → 固定 7 列后是第 1 个源码字符
            sb.append('\n').append(pad).append(lineNo).append(" | ").append(sourceLine.stripTrailing());
            sb.append('\n').append("     | ").append(" ".repeat(Math.max(0, col - 1)))
              .append("^".repeat(Math.max(1, length)));
        }
        for (String h : hints) {
            if (h == null || h.isBlank()) continue;
            sb.append('\n').append("  ").append(h);
        }
        return sb.toString();
    }

    /** 单行摘要：{@code file:line:col msg}，用于日志首行与汇总清单。 */
    public String summary() {
        return file + ":" + line + ":" + col + " " + getMessage();
    }

    /** 位置不变、只换消息（把「期望 X 实际 Y」包一层时用）。 */
    public ScriptError withMessage(String message) {
        return new ScriptError(file, line, col, length, sourceLine, message, hints, getCause());
    }

    /** 追加一条候选/提示，返回新实例（保持原位置）。 */
    public ScriptError withHint(String hint) {
        List<String> all = new ArrayList<>(hints);
        all.add(hint);
        return new ScriptError(file, line, col, length, sourceLine, getMessage(), all, getCause());
    }
}
