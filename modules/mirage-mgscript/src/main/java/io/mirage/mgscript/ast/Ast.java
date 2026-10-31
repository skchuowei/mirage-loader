package io.mirage.mgscript.ast;

import java.util.List;
import java.util.Map;

/**
 * MGScript 抽象语法树。全部用 record + sealed，求值器靠 switch pattern 分派，
 * 新增节点类型时编译器会强制所有分支处理（不会漏 case 导致静默返回 null）。
 *
 * <p>位置信息（line/col）只用于报错，不进值模型。
 */
public final class Ast {

    private Ast() {}

    public sealed interface Node permits Expr, Stmt, Block {
        int line();
        int col();
    }

    // ---- 表达式 ----

    public sealed interface Expr extends Node permits
            Literal, Str, Var, Get, Index, Binary, Unary, Call,
            ListLit, MapLit, RangeLit, FnLit {}

    /** 数字统一 double；字符串不在这里（见 {@link Str}，因为要支持插值）。 */
    public record Literal(Object value, int line, int col) implements Expr {}

    /** 字符串字面量的分段：{@code {"HP ", expr, "!"}}，求值时按顺序拼接。 */
    public record Str(List<Object> parts, int line, int col) implements Expr {}

    public record Var(String name, int line, int col) implements Expr {}

    public record Get(Expr target, String name, int line, int col) implements Expr {}

    public record Index(Expr target, Expr index, int line, int col) implements Expr {}

    /** op 取值: {@code + - * / % == != < <= > >= and or in}。 */
    public record Binary(String op, Expr left, Expr right, int line, int col) implements Expr {}

    /** op 取值: {@code - not}。 */
    public record Unary(String op, Expr operand, int line, int col) implements Expr {}

    public record Call(Expr callee, List<Expr> args, int line, int col) implements Expr {}

    public record ListLit(List<Expr> items, int line, int col) implements Expr {}

    /** 键值对保持书写顺序（LinkedHashMap 语义），mod 属性块依赖这个顺序做诊断。 */
    public record MapLit(List<Map.Entry<Expr, Expr>> entries, int line, int col) implements Expr {}

    /** {@code 0..10}（左闭右开）/ {@code 0..=10}（闭区间）。 */
    public record RangeLit(Expr from, Expr to, boolean inclusiveEnd, int line, int col) implements Expr {}

    /** 匿名/具名函数字面量（`fn name(a, b)`）；typeHint 可为 null。 */
    public record FnLit(String name, List<Param> params, Block body, int line, int col) implements Expr {}

    public record Param(String name, String typeHint) {}

    // ---- 语句 ----

    public sealed interface Stmt extends Node permits
            ExprStmt, Assign, Shared, If, For, While, Return, Break, Continue,
            FnDecl, ModDecl, ItemDecl, BlockDecl, OnDecl, Prop {}

    public record ExprStmt(Expr expr, int line, int col) implements Stmt {}

    /** 目标只能是 Var/Get/Index（赋值目标校验在 parser 做，避免求值期才发现）。 */
    public record Assign(Expr target, Expr value, int line, int col) implements Stmt {}

    public record Shared(String name, Expr value, int line, int col) implements Stmt {}

    public record IfBranch(Expr cond, Block body, int line) {}

    public record If(List<IfBranch> branches, Block elseBody, int line, int col) implements Stmt {}

    public record For(String varName, Expr iterable, Block body, int line, int col) implements Stmt {}

    public record While(Expr cond, Block body, int line, int col) implements Stmt {}

    /** value 为 null 表示 `return` 裸返回。 */
    public record Return(Expr value, int line, int col) implements Stmt {}

    public record Break(int line, int col) implements Stmt {}

    public record Continue(int line, int col) implements Stmt {}

    public record FnDecl(FnLit fn, int line, int col) implements Stmt {}

    /** {@code mod "id" version "1.0.0"}：顶层唯一，重复声明在后面覆盖并 WARN。 */
    public record ModDecl(String id, Map<String, Expr> props, int line, int col) implements Stmt {}

    /** {@code item "ruby"} + 缩进属性块。声明式，静态提取成注册项，不进求值流程。 */
    public record ItemDecl(String name, Map<String, Expr> props, int line, int col) implements Stmt {}

    public record BlockDecl(String name, Map<String, Expr> props, int line, int col) implements Stmt {}

    /**
     * {@code on tick(server) every 20}。
     *
     * @param every null 表示每个事件都触发；非 null 时是 tick 类事件的周期（单位：tick）
     */
    public record OnDecl(String event, List<String> params, Expr every, Block body,
                         int line, int col) implements Stmt {}

    /** 声明块内的属性行：{@code stack 64} / {@code name "Ruby"}。 */
    public record Prop(String key, Expr value, int line, int col) implements Stmt {}

    // ---- 块 ----

    public record Block(List<Stmt> stmts, int line, int col) implements Node {}
}
